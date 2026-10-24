#!/bin/bash
# T12 — faza 3 (sistemski asistent, zaklenjen telefon) na TESTNEM telefonu.
# Uporaba: bash ops/t12-faza3.sh <serial> [PIN]
# Izpis je surov; PASS/FAIL oceni oblačna seja (pričakovanja v PLAN.md, faza 3 → testi A0–A11).
# Skripta za čas testa nastavi PIN (zaklep je smiseln le z njim) in ga na koncu odstrani.
# Ne pošilja SMS-ov, ne kliče in ne bere vsebine obvestil — pravila zaklepa preverja na
# »nevarnih« orodjih, ki se ob zaklepu NE smejo izvesti.
set -u
S=${1:?uporaba: t12-faza3.sh <serial> [PIN]}
PIN=${2:-1590}
sh()  { adb -s "$S" shell "$@"; }
sec() { echo; echo "== $* =="; }
dbg() { sh "am broadcast -a si.sopotnik.DEBUG_DUMP $* si.sopotnik" 2>&1 | grep -iE "error|exception" ; sleep 1; }
lg()  { sh "logcat -v time -s Sopotnik:* -d" | grep -E "$1" | tail -n "${2:-40}"; }
win() { sh "dumpsys window" | grep -iE "mCurrentFocus|VoiceInteraction|isKeyguardShowing|mShowingLockscreen|KeyguardShowing" | head -8; }
rec() { sh "dumpsys audio" | grep -iE -A4 "rec.*sopotnik|sopotnik.*rec|uid:.*si.sopotnik" | head -16; }
lockit() { sh input keyevent KEYCODE_SLEEP; sleep 6; sh input keyevent KEYCODE_WAKEUP; sleep 2; }
unlock() {
  sh input keyevent KEYCODE_WAKEUP; sleep 1
  sh input swipe 540 1900 540 600 250; sleep 1
  sh input text "$PIN"; sh input keyevent KEYCODE_ENTER; sleep 3
}
TOOLS="get_time get_weather media_control play_music set_volume torch end_conversation navigate find_contact \
call_contact read_notifications read_notification open_notification send_reply compose_message send_message \
open_conversation open_app read_screen tap_item type_text scroll_screen go_back ask_brain poisci_zapiske \
preberi_zapisek shrani_zapisek neznano_orodje"

echo "T12 $(date -u +%FT%TZ) serial=$S"

sec A0 identifikacija
for p in ro.product.model ro.build.version.release ro.build.version.security_patch ro.miui.ui.version.name ro.miui.build.region; do
  echo "$p=$(sh getprop $p)"
done
sh dumpsys package si.sopotnik | grep -m1 versionName
MODE0=live

sec A1 vloga asistenta
REC0=$(sh settings get secure voice_recognition_service)
sh cmd package resolve-activity --brief -a android.settings.VOICE_INPUT_SETTINGS | tail -2
sh cmd role add-role-holder --user 0 android.app.role.ASSISTANT si.sopotnik; echo "add-role-holder exit=$?"; sleep 3
sh cmd role get-role-holders --user 0 android.app.role.ASSISTANT
echo "assistant=$(sh settings get secure assistant)"
echo "voice_interaction_service=$(sh settings get secure voice_interaction_service)"
echo "voice_recognition_service pred=$REC0 po=$(sh settings get secure voice_recognition_service)"
sh dumpsys voiceinteraction | head -30
sh logcat -c; dbg --ez assist true; lg "DEBUG_ASSIST|F3 vis"

sec A2 mirovanje "(ne sme snemati)"
sh dumpsys activity processes si.sopotnik | grep -iE "curCapability|setCapability|procState" | head -6
rec; echo "(prazno zgoraj = ni snemanja)"

sec A3 sprozitev odklenjeno "(klasicni nacin)"
dbg --es mode classic
sh logcat -c; sh input keyevent KEYCODE_ASSIST; sleep 3; win; rec; sleep 12
lg "F3 |STT|nič slišanega|DEBUG"

sec A4 dvojni sprozilec in barge-in
sh logcat -c
sh input keyevent KEYCODE_ASSIST; sleep 0.4; sh input keyevent KEYCODE_ASSIST; sleep 2
# dbg sestavi en niz za adb shell — večbesedno vrednost zato v ENOJNE narekovaje znotraj niza
dbg "--es say 'To je dolg testni stavek, ki ga Sven bere počasi, da ga lahko prekinemo s ponovnim pritiskom na tipko za asistenta, preden ga prebere do konca.'"
sleep 1.5; sh input keyevent KEYCODE_ASSIST; sleep 3
dbg --ez stop true; sleep 1
lg "F3 |DEBUG_STOP"

sec A5 sprozitev zaklenjeno
sh locksettings set-pin "$PIN"; echo "set-pin exit=$?"
lockit
sh logcat -c; dbg --ez assist true
sh input keyevent KEYCODE_ASSIST; sleep 4; win; rec
adb -s "$S" exec-out screencap -p > /tmp/t12-a5.png 2>/dev/null && echo "posnetek: /tmp/t12-a5.png (NE commitaj; opiši z besedami)"
sleep 10
lg "DEBUG_ASSIST|F3 |STT"
dbg --ez stop true

sec A6 politika zaklepa "(cista funkcija; zaklenjeno, nato odklenjeno)"
sh logcat -c
for t in $TOOLS; do dbg --es lock_tool $t; done
dbg --es lock_tool navigate --es dest domov
dbg --es lock_tool navigate --es dest Ljubljana
lg "DEBUG_LOCK" 60
unlock
sh logcat -c
for t in read_notifications send_message read_screen navigate; do dbg --es lock_tool $t; done
lg "DEBUG_LOCK" 10

sec A6b uveljavljanje v zivi seji "(zaklenjeno; potrebuje gateway in Sven Live)"
dbg --es mode live
lockit
sh logcat -c
sh input keyevent KEYCODE_ASSIST; sleep 8
for t in read_notifications send_message read_screen tap_item open_app compose_message send_reply; do
  dbg --es rt_tool $t --es rt_args '{}'
done
sleep 2
lg "DEBUG_RT|F3 gate|orodje |F3 rt_reset|F3 lock" 40
unlock; sleep 3
lg "F3 lock|F3 rt_reset" 10
dbg --ez stop true

sec A7 zagon iz ozadja "(BAL; s Sopotnikom kot asistentom)"
sh input keyevent KEYCODE_HOME; sleep 2
sh logcat -c
dbg --es nav_bg Ljubljana; sleep 4
sh dumpsys activity activities | grep -m2 -E "topResumedActivity|mResumedActivity"
sh logcat -b main,system -d | grep -iE "Background activity|Abort background|MIUILOG- Permission Denied" | tail -5
lg "DEBUG_NAV_BG"
sh input keyevent KEYCODE_HOME

sec A8 slusalke in HFP
sh cmd package query-activities --brief -a android.intent.action.VOICE_COMMAND | tail -4
sh cmd package query-activities --brief -a android.speech.action.VOICE_SEARCH_HANDS_FREE | tail -4
sh logcat -c
sh am start -n si.sopotnik/.assist.VoiceCommandActivity -a android.intent.action.VOICE_COMMAND >/dev/null; sleep 4
lg "F3 voicecmd|F3 show|F3 start"
dbg --ez stop true

sec A9 dolg pritisk power
echo "long_press_power_key=$(sh settings get system long_press_power_key)"
echo "power_button_long_press=$(sh settings get global power_button_long_press)"
sh logcat -c
sh input keyevent --longpress KEYCODE_POWER; sleep 3
lg "F3 show"
adb -s "$S" exec-out screencap -p > /tmp/t12-a9.png 2>/dev/null && echo "posnetek: /tmp/t12-a9.png (NE commitaj)"
sh input keyevent KEYCODE_BACK
dbg --ez stop true

sec A10 obstojnost vloge
sh am force-stop si.sopotnik; sleep 2
echo "po force-stop:"; sh cmd role get-role-holders --user 0 android.app.role.ASSISTANT
sh cmd role add-role-holder --user 0 android.app.role.ASSISTANT si.sopotnik; sleep 2
echo "po ponovni dodelitvi:"; sh cmd role get-role-holders --user 0 android.app.role.ASSISTANT

sec A11 zasebnost na koncu
dbg --ez stop true; sleep 3
rec; echo "(prazno zgoraj = ni snemanja)"
sh dumpsys activity services si.sopotnik | grep -E "ServiceRecord.*SessionService|isForeground" | head -4
echo "(SessionService ne sme biti v ospredju)"

sec POSPRAVLJANJE
unlock
sh locksettings clear --old "$PIN"; echo "clear exit=$?"
dbg --es mode "$MODE0"
dbg --ez assist true; lg "DEBUG_ASSIST" 2
echo "T12 konec $(date -u +%FT%TZ)"
