# frozen_string_literal: true

require 'optparse'
require 'json'

module OnxApkBuilder
  class CLI
    def self.start(argv = ARGV)
      new(argv).run
    end

    def initialize(argv)
      @argv = argv
      @options = {
        url: nil,
        name: 'Membersite',
        package: nil,
        wlb: nil,
        bootstrap_url: nil,
        bootstrap_base_url: ENV['APK_BOOTSTRAP_BASE_URL'] || ENV['API_BASE_URL'],
        out: File.join(ROOT, 'out')
      }
    end

    def run
      parse!
      result = Builder.new(
        url: @options[:url],
        name: @options[:name],
        package: @options[:package],
        wlb: @options[:wlb],
        bootstrap_url: @options[:bootstrap_url],
        bootstrap_base_url: @options[:bootstrap_base_url],
        out_dir: @options[:out]
      ).execute!

      puts JSON.generate(
        event: 'done',
        apk_path: result.apk_path,
        app_name: result.app_name,
        package_name: result.package_name,
        membersite_url: result.membersite_url
      )
      0
    rescue StandardError => e
      warn("[onx-apk-builder] ERROR: #{e.message}")
      puts JSON.generate(event: 'error', message: e.message)
      1
    end

    private

    def parse!
      parser = OptionParser.new do |opts|
        opts.banner = 'Usage: bin/build-apk --url URL [options]'
        opts.on('--url URL', 'Membersite URL / offline fallback (required)') { |v| @options[:url] = v }
        opts.on('--name NAME', 'App display name') { |v| @options[:name] = v }
        opts.on('--package PKG', 'Android applicationId') { |v| @options[:package] = v }
        opts.on('--wlb WLB', 'WLB identifier (package slug + bootstrap key)') { |v| @options[:wlb] = v }
        opts.on('--bootstrap-url URL', 'Full bootstrap endpoint URL') { |v| @options[:bootstrap_url] = v }
        opts.on('--bootstrap-base-url URL', 'ONX origin used to build bootstrap URL') { |v| @options[:bootstrap_base_url] = v }
        opts.on('--out DIR', 'Output directory for APK') { |v| @options[:out] = v }
        opts.on('-h', '--help', 'Show help') do
          puts opts
          exit 0
        end
        opts.on('-v', '--version', 'Show version') do
          puts "onx-apk-builder #{VERSION}"
          exit 0
        end
      end
      parser.parse!(@argv)
      raise OptionParser::MissingArgument, '--url is required' if @options[:url].to_s.strip.empty?
    end
  end
end
