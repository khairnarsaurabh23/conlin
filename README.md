# JobPilot — LinkedIn job hunting assistant for Android

JobPilot checks LinkedIn for the jobs you want and saves every result. It scores each job against your skills and finds people who can refer you. It drafts personal referral notes and collects recruiter emails ("send your CV to …"), then sends your resume to the emails you approve. It also builds a detailed report of everything.

## What it does

| Feature | How it works |
|---|---|
| **Job preferences** | Add as many searches as you like: title or keywords, location, experience level, remote/hybrid/on-site, posted within, and words to skip (e.g. "senior"). |
| **Checks LinkedIn for jobs** | Reads LinkedIn's **public** job listings, the same ones anyone sees at linkedin.com/jobs without logging in. Your account is never used for this. You can also add the **JSearch API** (free key) to include Indeed, Glassdoor, Naukri and company sites. |
| **Runs automatically** | A background check runs every N hours (default 12) and notifies you: "14 new jobs · 3 recruiter emails to review · 2 follow-ups due". |
| **Stores references** | Every job is saved with its link, company, location, date, source, full description, a **match %** and which of your skills it mentions or misses. Duplicates across sources are merged. Each job has a status: New → Saved → Applied / Referral asked → Interview / Rejected. |
| **Finds referrers** | On a job, tap **Find referrers**. The built-in LinkedIn browser opens a people search for that company and role. Tap **Grab people** to add everyone on that page. Recruiters shown on public job pages are added automatically. |
| **Referral outreach (one tap)** | Each person gets a personal note (name, company, role, your top skills). **Copy & open** copies the note and opens their profile in the LinkedIn app. Then tap *Connect → Add a note → Paste → Send*. It tracks who you contacted and when, and reminds you to **follow up** after N days. |
| **Emails to recruiters** | Emails are pulled from job descriptions, from hiring posts (**Grab emails** in the LinkedIn tab), and from anything you share into the app. Each becomes a draft with your resume attached. You **review → approve → send all** at once through Gmail SMTP (with an App Password), or one by one through the Gmail app. There's a daily cap, and no address is ever emailed twice. |
| **Detailed report** | HTML report with totals, every search, top matches with skill match, the referral pipeline, follow-ups due, emails sent, skill gaps, companies hiring most, and the run history. Share it as a file or export everything to **CSV**. |
| **Share into the app** | In the LinkedIn app, tap Share on a **profile**, **job**, or **post**, then JobPilot. It adds the person, saves the job, or pulls the email out of the post. |

### Why messages aren't sent fully automatically
LinkedIn's terms ban bots that send invites or messages, and it detects them. Accounts that do this get restricted or banned. JobPilot does all the slow work (finding people, writing a personal note, copying it, opening the profile), so each referral request takes about 5 seconds. Pressing **Send** is still you, which keeps your account safe. The in-app browser only reads the page when you tap a button. It never clicks, scrolls or messages by itself. Keep it to roughly 10–20 invites a day.

## Build the APK

### Option A — Android Studio (easiest)
1. Install **Android Studio** (free): https://developer.android.com/studio
2. Unzip this folder. In Android Studio, choose **File → Open** and select the `JobPilot` folder. Wait for "Gradle sync" to finish (the first time downloads a few hundred MB).
3. Connect your phone with USB debugging on and press ▶ **Run**. Or use **Build → Build APK(s)** (newer versions: *Build → Generate App Bundles or APKs → Generate APKs*): the APK lands in `app/build/outputs/apk/`.

### Option B — GitHub builds it for you
1. Create a new **private** GitHub repo and upload this folder.
2. GitHub Actions runs `.github/workflows/build-apk.yml` automatically. Open the **Actions** tab → latest run → download **JobPilot-apk**.
3. Copy the APK to your phone and open it (allow "Install unknown apps").

Requirements: Android 8.0 or newer.

## First-time setup (5 minutes)
1. **Home → ⚙ Settings**
   - **Add a job search**, e.g. *Android Developer · Pune, India · Entry level · Past week*.
   - Fill in name, headline, **skills** (comma separated; these drive the match %), years of experience, phone and LinkedIn URL.
   - **Choose resume** (PDF).
   - Optional: turn on **JSearch** and paste a free key from rapidapi.com (search "JSearch", subscribe to the Basic plan).
   - Optional: to send all approved emails in one tap, turn on Google 2-Step Verification, create an **App Password** at https://myaccount.google.com/apppasswords and paste it in with your Gmail address. Without it, each email opens in the Gmail app ready to send.
   - Tap **Save**.
2. Open the **LinkedIn** tab and sign in once with your email and password (Google sign-in doesn't work inside in-app browsers). Optional: open **My profile**, then ⋮ → *Import my profile*.
3. **Home → Check LinkedIn for new jobs now.**

## Daily routine
1. Check the notification, or open **Home** and look at the best new matches.
2. **Jobs**: open a good one, then **Open / Apply**, then **Find referrers → Grab people**.
3. **People**: go down the list tapping **Copy & open → paste → Send**. Mark replies and referrals.
4. **Emails**: review drafts, then **Approve all → Send approved**.
5. **Home → Detailed report** whenever you want the full picture.

To find more recruiter emails: **Jobs → ⋮ → Find hiring posts with emails**. Scroll the posts and tap "…see more" on promising ones so the full text loads, then tap **Grab emails**.

## Notes and limits
- LinkedIn sometimes changes its public job pages or rate-limits heavy use. If LinkedIn searches stop returning results, JSearch keeps working, and the run log on Home shows the error.
- Data stays on your phone (`jobpilot.json` in app storage). The SMTP app password is stored in the app's private storage. Use an App Password, never your real Gmail password, and you can revoke it any time.
- Gmail limits personal accounts to a few hundred emails a day. JobPilot's default cap is 25/day with a few seconds between sends, so you don't look like spam.
- The connection-note limit depends on your LinkedIn plan. Set it in Settings (default 300 characters). Notes are trimmed to fit.

## Project layout
```
app/src/main/java/com/jobpilot/
  data/     Models.kt (profile, searches, jobs, people, emails), Store.kt (JSON persistence)
  net/      LinkedInJobs.kt (public job search + job page), JSearch.kt, Http.kt
  core/     JobRunner.kt (search → dedupe → score → enrich → queue outreach, share handling)
            Text.kt (match scoring, email extraction, templates), Mailer.kt (SMTP / Gmail), Report.kt
  work/     SearchWorker.kt (background checks + notifications)
  ui/       Home, Jobs, People, Emails, LinkedIn browser, Settings, Report screens (Jetpack Compose)
```
