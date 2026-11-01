# Ski CalSync

Keeps Samsung Calendar and Apple Calendar in sync, both ways, from your Samsung phone.

- **iCloud → Samsung (two-way):** your iCloud calendars show up inside Samsung Calendar. Add, edit or delete an event on either phone and it syncs to the other.
- **Samsung → iPhone:** pick any Samsung / Google / on-phone calendars and they're copied into an iCloud calendar called **Samsung**, so they show in Apple Calendar on your iPhone. Edit those on the Samsung side.

Syncs every 15 minutes, right after you change an iCloud event on the phone, and whenever you tap **Sync now**.

## Setup

1. Push this folder to a GitHub repo (e.g. `Ski-CalSync`). GitHub Actions builds the APK — grab **Ski-CalSync-apk** from the run's Artifacts.
2. Install the APK on your Samsung phone and allow calendar access.
3. On a computer or iPhone, go to **account.apple.com → Sign-In and Security → App-Specific Passwords** and create one (call it "Samsung").
4. In the app, enter your Apple ID and that app-specific password, then tap **Connect**.
5. In Samsung Calendar, open the menu → **Manage calendars** and make sure the iCloud calendars are ticked.
6. Back in the app, switch on any Samsung calendars you want on your iPhone.

## Notes

- Your password stays on the phone in Android's account manager and is only sent to iCloud.
- If the same event is edited on both phones before a sync, the iCloud version wins.
- Shared calendars you can only view stay view-only on the Samsung side.
- Disconnecting removes the iCloud calendars from the phone; nothing is deleted from iCloud.
- Samsung may pause background sync to save battery: Settings → Apps → Ski CalSync → Battery → **Unrestricted** keeps it running on schedule.

## Project layout

- `ical/ICal.kt` — iCalendar parser/writer (time zones, all-day, recurring events, reminders)
- `dav/CalDavClient.kt` — iCloud CalDAV client
- `sync/SyncEngine.kt` — two-way iCloud ⇄ phone sync
- `sync/MirrorSync.kt` — Samsung calendars → iCloud "Samsung" calendar
- `sync/LocalStore.kt` — Android calendar database (what Samsung Calendar reads)
- `ui/MainActivity.kt` — sign-in and settings screens
