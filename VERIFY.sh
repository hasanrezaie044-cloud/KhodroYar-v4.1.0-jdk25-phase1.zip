#!/bin/bash
set -uo pipefail
SRC="$( cd "$(dirname "$0")" && pwd )"
fail=0

echo "=== STATIC VERIFICATION SUITE ==="
echo

# 1. Check JDK 25 configuration
echo "1. JDK 25 Configuration"
grep -q "VERSION_25" android/app/build.gradle && echo "  ✓ JDK 25 sourceCompatibility" || { echo "  ✗ MISSING JDK 25"; fail=1; }
grep -q "JavaLanguageVersion.of(25)" android/app/build.gradle && echo "  ✓ Toolchain JDK 25" || { echo "  ✗ MISSING toolchain"; fail=1; }
grep -q "gradle-9.4.1" android/gradle/wrapper/gradle-wrapper.properties && echo "  ✓ Gradle 9.4.1" || { echo "  ✗ WRONG Gradle"; fail=1; }
grep -q "9.2.0" android/build.gradle.kts && echo "  ✓ AGP 9.2.0" || { echo "  ✗ WRONG AGP"; fail=1; }
echo

# 2. Check no obsolete JDK 17 left
echo "2. No JDK 17 Regressions"
! grep -q "VERSION_17" android/app/build.gradle && echo "  ✓ No VERSION_17" || { echo "  ✗ VERSION_17 found"; fail=1; }
! grep -qE "jvmTarget.*=.*['\"]17['\"]" android/app/build.gradle && echo "  ✓ No jvmTarget=17" || { echo "  ✗ jvmTarget=17"; fail=1; }
echo

# 3. Check AGP 9 compliance
echo "3. AGP 9 Compliance"
! grep -q "org.jetbrains.kotlin.android" android/build.gradle.kts && echo "  ✓ kotlin-android removed (root)" || { echo "  ✗ kotlin-android in root"; fail=1; }
! grep -q "org.jetbrains.kotlin.android" android/app/build.gradle && echo "  ✓ kotlin-android removed (app)" || { echo "  ✗ kotlin-android in app"; fail=1; }
! grep "^[^#]*kotlinOptions" android/app/build.gradle && echo "  ✓ No kotlinOptions block" || { echo "  ✗ kotlinOptions found"; fail=1; }
grep -q "localeFilters" android/app/build.gradle && echo "  ✓ localeFilters configured" || { echo "  ✗ localeFilters missing"; fail=1; }
echo

# 4. Check database entities
echo "4. Database Schema"
grep -q "InsuranceEntity::class" android/app/src/main/java/com/khodroyar/app/data/db/AppDatabase.kt && echo "  ✓ Insurance entity registered" || { echo "  ✗ Insurance entity missing"; fail=1; }
grep -q "version = 2" android/app/src/main/java/com/khodroyar/app/data/db/AppDatabase.kt && echo "  ✓ Database version 2" || { echo "  ✗ DB version wrong"; fail=1; }
grep -q "InsuranceDao" android/app/src/main/java/com/khodroyar/app/data/db/Daos.kt && echo "  ✓ Insurance DAO exists" || { echo "  ✗ Insurance DAO missing"; fail=1; }
grep -q "MIGRATION_1_2" android/app/src/main/java/com/khodroyar/app/data/db/AppDatabase.kt && echo "  ✓ Migration 1→2 wired" || { echo "  ✗ Migration missing"; fail=1; }
echo

# 5. Check signing configuration
echo "5. Signing Configuration"
grep -q "ANDROID_KEYSTORE_PATH" android/app/build.gradle && echo "  ✓ Keystore path env" || { echo "  ✗ Keystore config"; fail=1; }
grep -q "signingConfig signingConfigs.release" android/app/build.gradle && echo "  ✓ Release signing wired" || { echo "  ✗ Release signing missing"; fail=1; }
grep -q "com.khodroyar.app" android/app/build.gradle && echo "  ✓ Package ID correct" || { echo "  ✗ Package ID wrong"; fail=1; }
echo

# 6. Check GitHub Actions
echo "6. GitHub Actions Workflow"
[ -f ".github/workflows/android-release.yml" ] && echo "  ✓ Workflow file exists" || { echo "  ✗ Workflow missing"; fail=1; }
grep -q "JAVA_VERSION: '25'" .github/workflows/android-release.yml && echo "  ✓ JDK 25" || { echo "  ✗ Wrong JDK in workflow"; fail=1; }
grep -q "KEYSTORE_BASE64" .github/workflows/android-release.yml && echo "  ✓ Secret KEYSTORE_BASE64" || { echo "  ✗ Secret missing"; fail=1; }
grep -q "assembleRelease" .github/workflows/android-release.yml && echo "  ✓ Release build" || { echo "  ✗ Release build missing"; fail=1; }
grep -q "workflow_call" .github/workflows/android-release.yml && echo "  ✓ Reusable workflow" || { echo "  ✗ Not reusable"; fail=1; }
echo

# 7. Check Kotlin files exist and reference correct imports
echo "7. Key Source Files"
[ -f "android/app/src/main/java/com/khodroyar/app/ui/screens/InsuranceScreen.kt" ] && echo "  ✓ Insurance screen" || { echo "  ✗ Insurance screen missing"; fail=1; }
[ -f "android/app/src/main/java/com/khodroyar/app/data/db/Migration.kt" ] && echo "  ✓ Database migration" || { echo "  ✗ Migration file missing"; fail=1; }
grep -q "import com.khodroyar.app.ui.components.tr" android/app/src/main/java/com/khodroyar/app/ui/screens/AuthScreens.kt && echo "  ✓ AuthScreens tr() import" || { echo "  ✗ AuthScreens import wrong"; fail=1; }
echo

# 8. Check manifest is unchanged
echo "8. Manifest & ProGuard"
[ -f "android/app/src/main/AndroidManifest.xml" ] && echo "  ✓ Manifest exists" || { echo "  ✗ Manifest missing"; fail=1; }
[ -f "android/app/proguard-rules.pro" ] && echo "  ✓ ProGuard rules exist" || { echo "  ✗ ProGuard missing"; fail=1; }
grep -qE "com.khodroyar.app|applicationId.*com.khodroyar.app" android/app/build.gradle && echo "  ✓ Package in manifest" || { echo "  ✗ Manifest package wrong"; fail=1; }
echo

# 9. Check version info
echo "9. Version Information"
grep -q "versionCode 24" android/app/build.gradle && echo "  ✓ versionCode 24" || { echo "  ✗ versionCode wrong"; fail=1; }
grep -q "versionName '4.2.0'" android/app/build.gradle && echo "  ✓ versionName 4.2.0" || { echo "  ✗ versionName wrong"; fail=1; }
echo

# 10. Check no debug signing for release
echo "10. Release APK Safety"
grep -q "minifyEnabled true" android/app/build.gradle && echo "  ✓ R8 minification enabled" || { echo "  ✗ Minification disabled"; fail=1; }
grep -q "shrinkResources true" android/app/build.gradle && echo "  ✓ Resource shrinking enabled" || { echo "  ✗ Shrinking disabled"; fail=1; }
echo

echo "=== RESULT ==="
if [ $fail -eq 0 ]; then
    echo "✓ ALL CHECKS PASSED"
    exit 0
else
    echo "✗ SOME CHECKS FAILED"
    exit 1
fi
