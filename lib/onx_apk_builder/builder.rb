# frozen_string_literal: true

require 'open-uri'
require 'fileutils'
require 'open3'
require 'json'
require 'pathname'
require 'securerandom'
require 'rbconfig'
require 'erb'
require 'nokogiri'

module OnxApkBuilder
  class Builder
    ICON_SIZES = {
      'mipmap-mdpi' => 48,
      'mipmap-hdpi' => 72,
      'mipmap-xhdpi' => 96,
      'mipmap-xxhdpi' => 144,
      'mipmap-xxxhdpi' => 192
    }.freeze

    Result = Struct.new(:apk_path, :app_name, :package_name, :membersite_url, :stages, keyword_init: true)

    def initialize(url:, name: nil, package: nil, wlb: nil, bootstrap_url: nil, bootstrap_base_url: nil, out_dir: nil, workdir: nil, on_stage: nil)
      @url = normalize_url(url)
      @name = (name.presence || 'Membersite').to_s[0, 30]
      @wlb = wlb.to_s
      @package = package.presence || default_package
      @bootstrap_url = bootstrap_url.to_s.strip
      @bootstrap_base_url = bootstrap_base_url.to_s.strip.sub(%r{/+\z}, '')
      @out_dir = Pathname(out_dir || File.join(ROOT, 'out'))
      @workdir = Pathname(workdir || File.join(ROOT, 'tmp', "build_#{SecureRandom.hex(6)}"))
      @on_stage = on_stage || method(:default_stage_logger)
      @completed_stages = []
    end

    def execute!
      validate!
      stage!('open_membersite', "Opening #{@url}")
      html = fetch_membersite_html

      stage!('fetch_assets', 'Downloading logo & favicon')
      assets = extract_and_download_assets(html)

      stage!('generate_icons', 'Generating mipmap icons + splash')
      prepare_project!(assets)

      stage!('build_apk', 'Running Gradle assembleDebug')
      apk_path = compile_apk!

      FileUtils.mkdir_p(@out_dir)
      final_path = @out_dir.join(File.basename(apk_path))
      FileUtils.cp(apk_path, final_path)

      stage!('ready', "APK ready: #{final_path}")
      Result.new(
        apk_path: final_path.to_s,
        app_name: @name,
        package_name: @package,
        membersite_url: @url,
        stages: @completed_stages
      )
    ensure
      FileUtils.rm_rf(@workdir) if @workdir && @workdir.directory?
    end

    private

    def validate!
      raise ArgumentError, 'membersite URL is required' if @url.to_s.strip.empty?
      raise "Android template missing: #{TEMPLATE_ROOT}" unless Dir.exist?(TEMPLATE_ROOT)
      raise 'ANDROID_HOME / ANDROID_SDK_ROOT is not set' if android_sdk_root.to_s.empty?
      raise "Android SDK not found at #{android_sdk_root}" unless Dir.exist?(android_sdk_root)
    end

    def default_package
      slug = @wlb.to_s.downcase.gsub(/[^a-z0-9]+/, '')
      slug = 'app' if slug.empty?
      "com.onx.membersite.#{slug}"
    end

    def normalize_url(raw)
      value = raw.to_s.strip
      return value if value.empty?

      value = "https://#{value}" unless value.match?(%r{\Ahttps?://}i)
      URI.parse(value).to_s
    end

    def android_sdk_root
      ENV['ANDROID_HOME'].to_s.strip.empty? ? ENV['ANDROID_SDK_ROOT'].to_s.strip : ENV['ANDROID_HOME'].to_s.strip
    end

    def stage!(key, message)
      defn = STAGES.find { |s| s[:key] == key } || { key: key, label: key, progress: 0 }
      payload = {
        stage: key,
        label: defn[:label],
        progress: defn[:progress],
        message: message
      }
      @completed_stages << payload
      @on_stage.call(payload)
    end

    def default_stage_logger(payload)
      warn("[onx-apk-builder] #{payload[:progress]}% #{payload[:stage]} — #{payload[:message]}")
      puts JSON.generate(event: 'stage', **payload)
    end

    def fetch_membersite_html
      URI.open(
        @url,
        'User-Agent' => "ONX-ApkBuilder/#{VERSION}",
        read_timeout: 30,
        open_timeout: 15
      ).read
    rescue StandardError => e
      raise "Failed to open membersite (#{@url}): #{e.message}"
    end

    def extract_and_download_assets(html)
      doc = Nokogiri::HTML(html)
      logo_url = absolutize(
        pick_attr(doc, 'img.logo, .logo img, .site-header img, header img', 'src') ||
          find_logo_src(doc)
      )
      favicon_url = absolutize(
        pick_attr(doc, 'link[rel="icon"], link[rel="shortcut icon"], link[rel*="icon"]', 'href')
      )

      branding_dir = @workdir.join('branding')
      FileUtils.mkdir_p(branding_dir)
      logo_path = branding_dir.join('logo.png')
      favicon_path = branding_dir.join('favicon.png')

      download_binary(logo_url, logo_path) if logo_url
      download_binary(favicon_url, favicon_path) if favicon_url

      source =
        if logo_path.file?
          logo_path
        elsif favicon_path.file?
          favicon_path
        end

      raise 'No logo/favicon found on membersite' if source.nil?

      { logo: source, favicon: (favicon_path.file? ? favicon_path : source) }
    end

    def pick_attr(doc, css, attr)
      node = doc.at_css(css)
      node && node[attr]
    end

    def find_logo_src(doc)
      doc.css('img').each do |img|
        alt = img['alt'].to_s.downcase
        return img['src'] if alt.include?('logo') || alt.include?('ogo')
      end
      nil
    end

    def prepare_project!(assets)
      FileUtils.mkdir_p(@workdir)
      FileUtils.cp_r(TEMPLATE_ROOT, @workdir.join('android_project'))
      project = @workdir.join('android_project')
      write_local_properties!(project)
      patch_app_identity!(project)
      generate_icons!(project, assets[:logo])
    end

    def write_local_properties!(project)
      sdk = android_sdk_root.to_s.gsub('\\', '/')
      File.write(project.join('local.properties'), "sdk.dir=#{sdk}\n")
    end

    def patch_app_identity!(project)
      app_gradle = project.join('app/build.gradle')
      content = File.read(app_gradle)
      content = content.sub(/applicationId\s+"[^"]+"/, "applicationId \"#{@package}\"")
      File.write(app_gradle, content)

      File.write(
        project.join('app/src/main/res/values/strings.xml'),
        <<~XML
          <?xml version="1.0" encoding="utf-8"?>
          <resources>
              <string name="app_name">#{xml_escape(@name)}</string>
              <string name="wlb">#{xml_escape(wlb_key)}</string>
              <string name="bootstrap_url">#{xml_escape(resolved_bootstrap_url)}</string>
              <string name="membersite_url">#{xml_escape(@url)}</string>
              <string name="membersite_unreachable">Situs tidak bisa diakses. Menunggu URL baru atau coba lagi.</string>
              <string name="retry">Coba lagi</string>
          </resources>
        XML
      )
    end

    def wlb_key
      value = @wlb.to_s.strip
      value.empty? ? 'app' : value
    end

    def resolved_bootstrap_url
      return @bootstrap_url unless @bootstrap_url.empty?
      return '' if @bootstrap_base_url.empty?

      "#{@bootstrap_base_url}/api/v1/apps/#{ERB::Util.url_encode(wlb_key)}/bootstrap"
    end

    def generate_icons!(project, logo_path)
      res = project.join('app/src/main/res')
      ICON_SIZES.each do |folder, size|
        dir = res.join(folder)
        FileUtils.mkdir_p(dir)
        %w[ic_launcher.png ic_launcher_round.png ic_launcher_foreground.png].each do |name|
          resize_to_square(logo_path, dir.join(name), size)
        end
      end
      drawable = res.join('drawable')
      FileUtils.mkdir_p(drawable)
      resize_to_square(logo_path, drawable.join('splash_logo.png'), 512)
    end

    def compile_apk!
      project = @workdir.join('android_project')
      gradlew = windows? ? 'gradlew.bat' : 'gradlew'
      gradlew_path = project.join(gradlew)
      FileUtils.chmod('+x', gradlew_path) unless windows?

      env = {
        'ANDROID_HOME' => android_sdk_root,
        'ANDROID_SDK_ROOT' => android_sdk_root,
        'JAVA_HOME' => ENV['JAVA_HOME'].to_s.strip.empty? ? guess_java_home : ENV['JAVA_HOME']
      }.compact

      command =
        if windows?
          ['cmd.exe', '/c', gradlew_path.to_s, 'assembleDebug', '--no-daemon']
        else
          [gradlew_path.to_s, 'assembleDebug', '--no-daemon']
        end

      stdout, status = Open3.capture2e(env, *command, chdir: project.to_s)
      apk = project.join('app/build/outputs/apk/debug/app-debug.apk')
      raise "Gradle build failed:\n#{stdout.to_s[-4000..-1]}" unless status.success? && apk.file?

      dest = @workdir.join("#{@package}.apk")
      FileUtils.cp(apk, dest)
      dest
    end

    def download_binary(url, path)
      return if url.to_s.empty?

      URI.open(url, 'User-Agent' => "ONX-ApkBuilder/#{VERSION}", read_timeout: 30, open_timeout: 15) do |io|
        File.binwrite(path, io.read)
      end
    rescue StandardError => e
      warn("[onx-apk-builder] download failed #{url}: #{e.message}")
    end

    def absolutize(url)
      return if url.to_s.empty?

      URI.join(@url, url).to_s
    rescue StandardError
      url.to_s.start_with?('http') ? url.to_s : nil
    end

    def resize_to_square(source, destination, size)
      begin
        require 'image_processing/vips'
        ImageProcessing::Vips
          .source(source.to_s)
          .resize_and_pad(size, size, background: [11, 11, 15])
          .call(destination: destination.to_s)
        return
      rescue LoadError, StandardError
        # fall through
      end

      begin
        require 'image_processing/mini_magick'
        ImageProcessing::MiniMagick
          .source(source.to_s)
          .resize_and_pad(size, size, background: '#0B0B0F')
          .call(destination: destination.to_s)
        return
      rescue LoadError, StandardError
        FileUtils.cp(source, destination)
      end
    end

    def xml_escape(value)
      value.to_s.gsub('&', '&amp;').gsub('<', '&lt;').gsub('>', '&gt;').gsub('"', '&quot;')
    end

    def guess_java_home
      candidates = [
        'C:/Program Files/Java/jdk-23',
        'C:/Program Files/Java/jdk-21',
        'C:/Program Files/Eclipse Adoptium/jdk-17*',
        '/usr/lib/jvm/java-17-openjdk-amd64',
        '/usr/lib/jvm/java-21-openjdk-amd64'
      ]
      candidates.each do |pattern|
        hit = Dir.glob(pattern).first
        return hit if hit && File.directory?(hit)
      end
      nil
    end

    def windows?
      !!(RbConfig::CONFIG['host_os'] =~ /mswin|mingw|cygwin/)
    end
  end
end

# Minimal ActiveSupport-like presence for plain Ruby.
class Object
  def presence
    return self unless respond_to?(:empty?)

    empty? ? nil : self
  end
end

class NilClass
  def presence
    nil
  end
end
