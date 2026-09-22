#!/usr/bin/env bash
# Offline verification: build-stack correctness + "nothing was removed" regression diff.
set -uo pipefail
ORIG="${1:-}"   # path to an extracted copy of the previous release, for the regression diff
NEW="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fail=1; }
chk()  { if eval "$2" >/dev/null 2>&1; then ok "$1"; else bad "$1"; fi }

# Comment lines are stripped before every "must NOT contain" check, so the explanatory
# comments in the build files (which name the old APIs on purpose) cannot fake a pass.
nc() { sed -E 's@//.*$@@; s@^[[:space:]]*#.*$@@' "$1"; }

echo "== A. JDK 25 migration =="
chk "gradle wrapper >= 9.4.1"                 "grep -q 'gradle-9.4.1-bin.zip' $NEW/android/gradle/wrapper/gradle-wrapper.properties"
chk "AGP 9.2.0 declared"                      "grep -q 'com.android.application\") version \"9.2.0\"' $NEW/android/build.gradle.kts"
chk "compose compiler plugin 2.3.10"          "grep -q 'plugin.compose\") version \"2.3.10\"' $NEW/android/build.gradle.kts"
chk "KSP 2.3.10 (AGP9 built-in-Kotlin fix)"   "grep -q 'devtools.ksp\") version \"2.3.10\"' $NEW/android/build.gradle.kts"
chk "sourceCompatibility = 25"                "grep -q 'sourceCompatibility JavaVersion.VERSION_25' $NEW/android/app/build.gradle"
chk "targetCompatibility = 25"                "grep -q 'targetCompatibility JavaVersion.VERSION_25' $NEW/android/app/build.gradle"
chk "java toolchain = 25"                     "grep -q 'JavaLanguageVersion.of(25)' $NEW/android/app/build.gradle"
chk "no JDK 17 left in app build"             "! nc $NEW/android/app/build.gradle | grep -q 'VERSION_17'"
chk "no jvmTarget = '17' left"                "! nc $NEW/android/app/build.gradle | grep -qE \"jvmTarget\""
chk "CI java-version = 25"                    "grep -q \"JAVA_VERSION: '25'\" $NEW/.github/workflows/android-release.yml"
chk "CI has no JDK 17 reference"              "! nc $NEW/.github/workflows/android-release.yml | grep -qE \"'17'\""

echo "== B. AGP 9 breaking-change compliance =="
chk "kotlin-android plugin removed (root)"    "! nc $NEW/android/build.gradle.kts | grep -q 'kotlin.android'"
chk "kotlin-android plugin removed (app)"     "! nc $NEW/android/app/build.gradle | grep -q 'kotlin.android'"
chk "android.kotlinOptions{} removed"         "! nc $NEW/android/app/build.gradle | grep -q 'kotlinOptions'"
chk "resourceConfigurations -> localeFilters" "grep -q \"localeFilters += \\['fa', 'en'\\]\" $NEW/android/app/build.gradle"
chk "no resourceConfigurations left"          "! nc $NEW/android/app/build.gradle | grep -q 'resourceConfigurations'"
chk "no builtInKotlin/newDsl opt-out"         "! nc $NEW/android/gradle.properties | grep -qE 'builtInKotlin|newDsl|enableLegacyVariantApi'"
chk "no kapt (incompatible w/ built-in)"      "! nc $NEW/android/app/build.gradle | grep -q 'kapt'"
chk "compileSdk >= 36 (AGP 9 floor)"          "grep -q 'compileSdk 36' $NEW/android/app/build.gradle"
chk "buildTools 36.0.0 (AGP 9 floor)"         "grep -q \"buildToolsVersion '36.0.0'\" $NEW/android/app/build.gradle"

echo "== C. Release identity & signing preserved =="
chk "applicationId com.khodroyar.app"         "grep -q \"applicationId 'com.khodroyar.app'\" $NEW/android/app/build.gradle"
chk "namespace com.khodroyar.app"             "grep -q \"namespace 'com.khodroyar.app'\" $NEW/android/app/build.gradle"
chk "versionCode unchanged (23)"              "grep -q 'versionCode 23' $NEW/android/app/build.gradle"
chk "versionName unchanged (4.1.0)"           "grep -q \"versionName '4.1.0'\" $NEW/android/app/build.gradle"
chk "release signingConfig still wired"       "grep -q 'signingConfig signingConfigs.release' $NEW/android/app/build.gradle"
chk "strict fail-if-no-keystore kept"         "grep -q 'Release signing configuration is missing' $NEW/android/app/build.gradle"
chk "R8 minify kept"                          "grep -q 'minifyEnabled true' $NEW/android/app/build.gradle"
chk "resource shrinking kept"                 "grep -q 'shrinkResources true' $NEW/android/app/build.gradle"
chk "proguard-rules.pro still referenced"     "grep -q \"'proguard-rules.pro'\" $NEW/android/app/build.gradle"
chk "core library desugaring kept"            "grep -q 'coreLibraryDesugaringEnabled true' $NEW/android/app/build.gradle"
for s in KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  chk "secret name '$s' unchanged"            "grep -q 'secrets.$s' $NEW/.github/workflows/android-release.yml"
done
chk "no secret echoed to logs"                "! nc $NEW/.github/workflows/android-release.yml | grep -qE 'echo .*secrets\.'"
chk "APK (not AAB) still the output"          "! nc $NEW/.github/workflows/android-release.yml | grep -qi 'bundleRelease\|[.]aab'"
chk "signed APK uploaded as artifact"         "grep -q 'upload-artifact' $NEW/.github/workflows/android-release.yml"
chk "workflow is reusable (workflow_call)"    "grep -q 'workflow_call:' $NEW/.github/workflows/android-release.yml"

echo "== D. Regression: nothing removed =="
# 1. every original file still present
missing=0
while IFS= read -r f; do
  [ -e "$NEW/$f" ] || { bad "MISSING FILE: $f"; missing=1; }
done < <(cd "$ORIG" && find . -type f | sed 's|^\./||' | sort)
[ $missing -eq 0 ] && ok "all $(cd "$ORIG" && find . -type f | wc -l) original files still present"

# 2. every Kotlin top-level declaration still present
python3 - "$ORIG" "$NEW" <<'PY'
import os,re,sys
orig,new=sys.argv[1],sys.argv[2]
pat=re.compile(r'^\s*(?:@\w+\s+)*(?:public |internal |private |abstract |sealed |open |data |enum |annotation |value )*'
               r'(fun|class|interface|object|enum class|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)', re.M)
def syms(root):
    out=set()
    for dp,_,fns in os.walk(root):
        for fn in fns:
            if fn.endswith('.kt'):
                for m in pat.finditer(open(os.path.join(dp,fn),encoding='utf-8').read()):
                    out.add(m.group(2))
    return out
a,b=syms(orig),syms(new)
lost=sorted(a-b)
if lost: print("  \033[31mFAIL\033[0m  %d Kotlin declarations lost: %s"%(len(lost),lost[:25])); sys.exit(1)
print("  \033[32mPASS\033[0m  all %d Kotlin declarations preserved (0 lost, %d added)"%(len(a),len(b-a)))
PY
[ $? -ne 0 ] && fail=1

# 3. Room schema untouched -> no migration needed, no data loss possible
chk "Room entity list unchanged"              "diff -q $ORIG/android/app/src/main/java/com/khodroyar/app/data/db/Entities.kt $NEW/android/app/src/main/java/com/khodroyar/app/data/db/Entities.kt"
chk "Room database version unchanged (1)"     "diff -q $ORIG/android/app/src/main/java/com/khodroyar/app/data/db/AppDatabase.kt $NEW/android/app/src/main/java/com/khodroyar/app/data/db/AppDatabase.kt"
chk "no destructive migration introduced"     "! grep -rq 'fallbackToDestructiveMigration' $NEW/android/app/src/main"
chk "DAO surface unchanged"                   "diff -q $ORIG/android/app/src/main/java/com/khodroyar/app/data/db/Daos.kt $NEW/android/app/src/main/java/com/khodroyar/app/data/db/Daos.kt"
chk "AndroidManifest unchanged"               "diff -q $ORIG/android/app/src/main/AndroidManifest.xml $NEW/android/app/src/main/AndroidManifest.xml"
chk "proguard rules unchanged"                "diff -q $ORIG/android/app/proguard-rules.pro $NEW/android/app/proguard-rules.pro"

# 4. feature inventory: every screen / nav destination / tool still reachable
python3 - "$ORIG" "$NEW" <<'PY'
import os,sys
orig,new=sys.argv[1],sys.argv[2]
rel='android/app/src/main/java/com/khodroyar/app'
feats={
 'Dashboard':'ui/screens/DashboardScreen.kt','Services':'ui/screens/ServicesScreen.kt',
 'ServiceForm':'ui/screens/ServiceFormScreen.kt','Personnel':'ui/screens/PersonnelDetailScreen.kt',
 'Finance/Loans/Purchases':'ui/screens/FinanceScreen.kt','Fuel':'ui/screens/FuelScreen.kt',
 'Maintenance/Oil':'ui/screens/MaintenanceScreen.kt','Rates':'ui/screens/RatesScreen.kt',
 'Reports':'ui/screens/ReportsScreen.kt','Settings':'ui/screens/SettingsScreen.kt',
 'Auth/PIN/Biometric':'ui/screens/AuthScreens.kt','Jalali calendar':'core/jalali/Jalali.kt',
 'Iran holidays':'core/holidays/IranHolidays.kt','Excel export':'export/XlsxWriter.kt',
 'PDF export':'export/PdfReportWriter.kt','Backup/Restore':'data/legacy/LegacyBackup.kt',
 'Backup files':'data/backup/BackupFiles.kt','Notifications':'notifications/Notifications.kt',
 'Reminders':'notifications/ReminderScheduler.kt','Reminder worker':'notifications/ReminderWorker.kt',
 'Loan calculator':'core/finance/LoanCalculator.kt','Income calculator':'core/rates/IncomeCalculator.kt',
 'Time rates':'core/rates/TimeRates.kt','Charts':'ui/components/Charts.kt',
 'Calendar screen':'ui/screens/CalendarScreen.kt','Theme (light/dark/system)':'ui/theme/CarManagerTheme.kt',
 'Navigation':'ui/nav/AppNavigation.kt','Shell/RTL/bottom nav':'ui/screens/Shell.kt',
 'Settings store':'data/prefs/SettingsStore.kt','Repository':'data/repo/AppRepository.kt',
 'Golden parity test':'android/app/src/test/java/com/khodroyar/app/core/GoldenParityTest.kt',
}
bad=0
for name,p in feats.items():
    path=p if p.startswith('android/') else os.path.join(rel,p)
    o=os.path.join(orig,path); n=os.path.join(new,path)
    if not os.path.exists(n): print("  \033[31mFAIL\033[0m  feature file gone: %s"%name); bad=1
    elif os.path.getsize(n) < os.path.getsize(o)*0.98:
        print("  \033[31mFAIL\033[0m  feature shrank: %s"%name); bad=1
print(("  \033[31mFAIL\033[0m  feature inventory" if bad else
       "  \033[32mPASS\033[0m  all %d tracked features present and not shrunk"%len(feats)))
sys.exit(bad)
PY
[ $? -ne 0 ] && fail=1

echo
if [ $fail -eq 0 ]; then echo "RESULT: ALL CHECKS PASSED"; else echo "RESULT: FAILURES PRESENT"; fi
exit $fail
