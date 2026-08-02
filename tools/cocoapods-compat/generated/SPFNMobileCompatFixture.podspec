# GENERATED FILE — DO NOT EDIT.
# Regenerate with: sh tools/cocoapods-compat/generate-podspec.sh --write
#
# INTERNAL COMPATIBILITY FIXTURE. This podspec is never published. CocoaPods is not
# a supported SPFN Mobile distribution channel; Swift Package Manager is the primary
# iOS channel. No pod name is claimed, no trunk publication is planned or promised,
# and tools/validate/validate.sh fails if a trunk publication command ever appears.
#
# It is generated from tools/module-graph.json and VERSION so it can never drift
# into a second implementation: every subspec points at the same Sources/ tree the
# SwiftPM manifest uses.
#
# Deliberately absent:
#   - s.platform / deployment targets and s.swift_versions — the D5 baseline lives in
#     Package.swift; this unpublished fixture does not restate a support surface
#   - a resolvable source / tag        (nothing is published and no tag exists)

Pod::Spec.new do |s|
  s.name             = 'SPFNMobileCompatFixture'
  s.version          = '0.1.0-alpha.1'
  s.summary          = 'Internal, unpublished CocoaPods compatibility fixture for the SPFN Mobile Swift sources.'
  s.description      = <<-DESC
                       Step 1 scaffold fixture. Describes the SwiftPM module graph to CocoaPods
                       from the same sources, purely to keep a single module graph verifiable.
                       Not a supported distribution channel and not published anywhere.
                       DESC
  s.homepage         = 'https://github.com/fxylabs/spfn-mobile'
  s.license          = { :type => 'MIT', :file => 'LICENSE' }
  s.authors          = { 'FXY Inc.' => 'https://github.com/fxylabs' }
  s.source           = { :git => 'file://LOCAL-ONLY-NO-PUBLISHED-SOURCE', :tag => 'NO-TAG-EXISTS' }

  s.subspec 'SPFNCore' do |sp|
    sp.source_files = 'Sources/SPFNCore/**/*.swift'
  end

  s.subspec 'SPFNGenerated' do |sp|
    sp.source_files = 'Sources/SPFNGenerated/**/*.swift'
    sp.dependency 'SPFNMobileCompatFixture/SPFNCore'
  end

  s.subspec 'SPFNAuth' do |sp|
    sp.source_files = 'Sources/SPFNAuth/**/*.swift'
    sp.dependency 'SPFNMobileCompatFixture/SPFNCore'
  end

  s.subspec 'SPFNClient' do |sp|
    sp.source_files = 'Sources/SPFNClient/**/*.swift'
    sp.dependency 'SPFNMobileCompatFixture/SPFNCore'
    sp.dependency 'SPFNMobileCompatFixture/SPFNAuth'
    sp.dependency 'SPFNMobileCompatFixture/SPFNGenerated'
  end

  s.subspec 'SPFNPersistence' do |sp|
    sp.source_files = 'Sources/SPFNPersistence/**/*.swift'
    sp.dependency 'SPFNMobileCompatFixture/SPFNCore'
  end

  s.subspec 'SPFNHybrid' do |sp|
    sp.source_files = 'Sources/SPFNHybrid/**/*.swift'
    sp.dependency 'SPFNMobileCompatFixture/SPFNCore'
    sp.dependency 'SPFNMobileCompatFixture/SPFNAuth'
  end

end
