# JobPilot

An Android app that runs a job search for you and asks before it does anything on your behalf. It finds jobs on LinkedIn, suggests people who could refer you, drafts the messages and recruiter emails, and tracks what happened. You approve each step.

## How it works

Everything the app wants to do arrives in the **Inbox** as a card:

| Card | What you decide |
|---|---|
| New job | Keep or skip. Kept jobs move to the Tracker. |
| Find people | Opens a people search for a kept job in the LinkedIn tab. |
| Connection request | Approve, edit the note, or skip. Suggestions are ranked: existing connections, your college, companies you worked at, then recruiters and people in the same role. |
| Referral ask | Appears once someone has accepted. Approve, edit, or skip. |
| Follow-up | One short follow-up if there is no reply after a few days. |
| Recruiter email | Addresses found in job posts and hiring posts. Approve, edit, or skip. |

Approved items move to **Ready to send**. For LinkedIn, the app copies the text and opens the profile; you tap Connect or Message and paste. Then you confirm whether it went through, and only then is it counted. The app never clicks or sends on LinkedIn by itself: automated sending is what LinkedIn detects and restricts.

Approved emails are sent from the app through Gmail (with an App Password), or opened one at a time in the Gmail app.

## Account safety

- Connection requests are capped per rolling 24 hours and per rolling 7 days (defaults 15 and 60).
- New use starts at a third of the caps and reaches the full amount in week three.
- Sends are spaced a few minutes apart.
- If the built-in browser sees LinkedIn's weekly-limit notice or a restriction notice, outreach pauses for 7 days. A security check right after outreach pauses it for 24 hours. You can also report a warning by hand from the Inbox.
- Old unanswered invites are flagged so you can withdraw them, and caps are halved if few invites are being accepted.

## Other features

- **Job search**: LinkedIn's public listings (no sign-in), optionally JSearch for Indeed, Glassdoor, Naukri and company sites. Runs in the background every 12 hours by default.
- **Match score** for each job against your skills.
- **Resume tailoring**: for a job, reorders your skills and summary around what the posting asks for and builds a one-page PDF, which is then attached to emails for that job. It rearranges what you entered; it does not invent experience.
- **Gmail reply tracking**: a read-only inbox check that marks accepted invites, replies, interview mails, rejections and bounces. Runs on the phone; nothing is uploaded.
- **Insights**: funnels for jobs, referrals and emails, reply rates, which template wording works better, and the full report (HTML) plus CSV export.
- **Share into the app**: share a profile, job or post from the LinkedIn app to JobPilot.

## Setup

1. Settings: add a job search, your name and skills, and attach your resume.
2. Optional but useful: college and past companies (for ranking), experience and education text (for tailored resumes).
3. Optional: Gmail address and an App Password (Google account, 2-Step Verification on, then myaccount.google.com/apppasswords). This enables one-tap sending and reply tracking. IMAP must be enabled in Gmail settings.
4. LinkedIn tab: sign in with email and password. This is only needed for finding people and hiring posts; job search and sharing from the LinkedIn app work without it.

## Building

Every push builds an APK with GitHub Actions. Download it from the Actions tab or from the `apk-build` branch. To build locally, open the folder in Android Studio.

The release build is signed with the key in `app/jobpilot.jks` so each build installs over the last. That key is in the repo, which is fine for a personal sideloaded app; replace it with a private key before publishing anywhere.

## Layout

```
app/src/main/java/com/jobpilot/
  data/   Models.kt, Store.kt (one JSON file in app storage)
  net/    LinkedInJobs.kt, JSearch.kt, Http.kt
  core/   JobRunner.kt   search, dedupe, score, suggestions, drafts
          Logic.kt       outreach budgets, ranking, tailoring, mail rules, insights
          MailScan.kt    read-only IMAP reply tracking
          Mailer.kt      SMTP and Gmail-app sending
          ResumePdf.kt   tailored resume PDF
          Report.kt      HTML report and CSV
  work/   SearchWorker.kt  background checks and notifications
  ui/     Inbox, Tracker, LinkedIn browser, Insights, Settings, Job detail
```
