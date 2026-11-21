# JobPilot

An Android app that runs a job search for you and asks before it does anything on your behalf. It finds jobs on LinkedIn, suggests people who could refer you, drafts the messages and recruiter emails, and tracks what happened. You approve each step.

## How it works

Everything the app wants to do arrives in the **Inbox** as a card:

| Card | What you decide |
|---|---|
| New job | Keep, skip, or **People to ask**: opens the company's people search and lists who works there, best first, each with the drafted message. Each card lists why it was suggested and what counts against it. Kept jobs move to the Tracker. |
| Find people | Opens a people search for a kept job in the LinkedIn tab. |
| Connection request | Approve, edit the note, or skip. Each card shows the evidence: their hiring post, the posting they are named on, or the openings at their company. The drafted note opens with that same reason. People who posted about hiring rank first, then existing connections, your college and past companies. |
| Referral ask | Appears once someone has accepted. Approve, edit, or skip. |
| Follow-up | One short follow-up if there is no reply after a few days. |
| Recruiter email | Addresses found in job posts and hiring posts. Approve, edit, or skip. |

Approved items move to **Ready to send**. For LinkedIn, the app copies the text and opens the profile in the Browse tab; you tap Connect or Message and paste. JobPilot reads the page you're on and logs the send when the request shows Pending or your message appears in the thread, so there's nothing to confirm. (If you choose to open profiles in the LinkedIn app instead, it asks.) The app never clicks or sends on LinkedIn by itself: automated sending is what LinkedIn detects and restricts.

Approved emails are sent from the app through Gmail (with an App Password), or opened one at a time in the Gmail app.

## Daily send session (3.1)

Approve people whenever you like; nothing is sent and nothing pings you. Once a day, at a time you pick (10:00 by default), one notification says how many are ready. Tap it, or **Start sending** in Send any time, and they come up one after another in Browse: tap Connect and Add a note (or Message), JobPilot types the approved text into the box, you tap Send, and the next person opens by itself when the page shows it went out. About 5 minutes for a day's batch. Daily and weekly limits still cap each session, and it stops if LinkedIn shows a warning. JobPilot types text but never presses Connect or Send.

Approved emails can drip out by themselves, one every 30 minutes within hours you set.

The Browse shortcuts highlight the site you're on (or show its name if it isn't one of them).

## Ideas taken from career-ops

- **Is it still open?** Kept jobs are rechecked every two days; postings that say "no longer accepting applications" or have gone are flagged and moved out of the Apply list.
- **Knock-outs**: the posting asks for more years than you have, a Master's/PhD you don't list, existing work authorization or residence elsewhere, or a security clearance. Shown in red on the Apply card and lower the score.
- **Reposts**: the same role at the same company relisted weeks later is flagged as possibly hard to fill or a ghost posting.
- **Fact check** on AI-tailored resumes: numbers, employers, titles and skills that aren't in your real resume are listed before the PDF is made.
- **Who you're writing to**: recruiters, hiring managers and peers get differently framed notes and referral asks (when your templates are left at the defaults).
- **People you already know**: import LinkedIn's Connections.csv (Settings). Jobs at companies where you know someone say so, and *Ask who I know* adds them with a referral message ready.
- **Follow-ups on applications**: a reminder 7 days after applying with no reply (with a drafted email if you applied by email), and a thank-you reminder after an interview.
- **More careers pages**: BambooHR, Workable, Teamtailor, Breezy and SmartRecruiters, alongside Ashby, Lever and Greenhouse. Paste the careers link in Settings.

## Applying

- **Apply list** (Inbox > Apply): every kept job you haven't applied to, with how to apply (company form, email or LinkedIn) and whether your resume is ready: *Ready to apply*, *Tailor first* (it shows the skills you have that your resume doesn't show), or *Weak fit*.
- **Company forms**: *Apply to ready ones* opens each form in Browse, one after another. JobPilot fills it from your **Application answers** (Settings), attaches your resume (the tailored one if there is one) when you tap the upload button, and with an AI key drafts answers to open questions. You check it and tap Submit; a "thanks for applying" page marks the job applied. JobPilot never presses Submit itself.
- **Email applications** can be sent automatically above a match score you choose, but only when the resume is ready (off by default).
- **LinkedIn jobs** open in the LinkedIn app; Easy Apply stays manual.

## Account safety

- **Direct messages** to people you aren't connected to (InMail, or free to Open Profile members) are offered for top suggestions, within a monthly allowance you set. With Premium, answered ones are given back, as LinkedIn returns the credit.

- Connection requests are capped per rolling 24 hours and per rolling 7 days (defaults 15 and 60).
- New use starts at a third of the caps and reaches the full amount in week three.
- Sends are spaced a few minutes apart.
- If the built-in browser sees LinkedIn's weekly-limit notice or a restriction notice, outreach pauses for 7 days. A security check right after outreach pauses it for 24 hours. You can also report a warning by hand from the Inbox.
- Old unanswered invites are flagged so you can withdraw them, and caps are halved if few invites are being accepted.

## Other features

- **Job search**: LinkedIn's public listings (no sign-in), Hacker News "Who is hiring" (startups), Remotive (remote), Arbeitnow (Europe, often with visa sponsorship), any startup careers pages you add on Ashby, Lever or Greenhouse, and optionally JSearch for Indeed, Glassdoor and Naukri. Runs in the background every 12 hours by default.
- **Categories**: every job is tagged Remote, Remote (other region), Abroad, Visa, Startup or Home, and the Inbox and Tracker filter by them.
- **Wellfound, YC and other sites**: they have no open feed, so open them in the Browse tab and tap Save this job, or share a job link to JobPilot.
- **Match score** from the title, your skills, the years asked for, how fresh the posting is, and what you kept or skipped before. Jobs under a threshold stay out of the inbox.
- **Hiring posts**: on a posts search in the LinkedIn tab, Grab people picks up authors of hiring posts together with what they wrote.
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
