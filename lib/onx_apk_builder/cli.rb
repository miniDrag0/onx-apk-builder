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
        opts.on('--url URL', 'Membersite URL (required)') { |v| @options[:url] = v }
        opts.on('--name NAME', 'App display name') { |v| @options[:name] = v }
        opts.on('--package PKG', 'Android applicationId') { |v| @options[:package] = v }
        opts.on('--wlb WLB', 'WLB identifier used for default package slug') { |v| @options[:wlb] = v }
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
