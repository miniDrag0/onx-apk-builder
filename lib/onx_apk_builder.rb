# frozen_string_literal: true

require_relative 'onx_apk_builder/version'
require_relative 'onx_apk_builder/builder'
require_relative 'onx_apk_builder/cli'

module OnxApkBuilder
  ROOT = File.expand_path('..', __dir__)
  TEMPLATE_ROOT = File.join(ROOT, 'android_template')

  STAGES = [
    { key: 'open_membersite', label: 'Open membersite URL', progress: 10 },
    { key: 'fetch_assets', label: 'Fetch brand assets (logo + favicon)', progress: 35 },
    { key: 'generate_icons', label: 'Generate icon mipmaps + splash', progress: 60 },
    { key: 'build_apk', label: 'Gradle assembleDebug', progress: 90 },
    { key: 'ready', label: 'APK ready', progress: 100 }
  ].freeze
end
