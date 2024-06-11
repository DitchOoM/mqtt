Pod::Spec.new do |spec|
    spec.name                     = 'mqtt_client'
    spec.version                  = '0.1.3'
    spec.homepage                 = ''
    spec.source                   = { :http=> ''}
    spec.authors                  = ''
    spec.license                  = ''
    spec.summary                  = ''
    spec.vendored_frameworks      = 'build/cocoapods/framework/mqtt_client.framework'
    spec.libraries                = 'c++'
    spec.ios.deployment_target    = '13.0'
    spec.osx.deployment_target    = '11.0'
    spec.tvos.deployment_target    = '13.0'
    spec.watchos.deployment_target    = '6.0'
    spec.dependency 'SocketWrapper'
                
    if !Dir.exist?('build/cocoapods/framework/mqtt_client.framework') || Dir.empty?('build/cocoapods/framework/mqtt_client.framework')
        raise "

        Kotlin framework 'mqtt_client' doesn't exist yet, so a proper Xcode project can't be generated.
        'pod install' should be executed after running ':generateDummyFramework' Gradle task:

            ./gradlew :mqtt-client:generateDummyFramework

        Alternatively, proper pod installation is performed during Gradle sync in the IDE (if Podfile location is set)"
    end
                
    spec.xcconfig = {
        'ENABLE_USER_SCRIPT_SANDBOXING' => 'NO',
    }
                
    spec.pod_target_xcconfig = {
        'KOTLIN_PROJECT_PATH' => ':mqtt-client',
        'PRODUCT_MODULE_NAME' => 'mqtt_client',
    }
                
    spec.script_phases = [
        {
            :name => 'Build mqtt_client',
            :execution_position => :before_compile,
            :shell_path => '/bin/sh',
            :script => <<-SCRIPT
                if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then
                  echo "Skipping Gradle build task invocation due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED environment variable set to \"YES\""
                  exit 0
                fi
                set -ev
                REPO_ROOT="$PODS_TARGET_SRCROOT"
                "$REPO_ROOT/../../../../../private/var/folders/pf/yv2h4kt547v0wlfdlclcwt300000gn/T/wrap4158loc/gradlew" -p "$REPO_ROOT" $KOTLIN_PROJECT_PATH:syncFramework \
                    -Pkotlin.native.cocoapods.platform=$PLATFORM_NAME \
                    -Pkotlin.native.cocoapods.archs="$ARCHS" \
                    -Pkotlin.native.cocoapods.configuration="$CONFIGURATION"
            SCRIPT
        }
    ]
                
end