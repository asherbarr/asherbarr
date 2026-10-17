// Builds the profile's stats and streak cards, counting only activity since START_DATE.
// Usage: GITHUB_TOKEN=... node scripts/generate-cards.js <output-dir>
const fs = require("fs");
const path = require("path");

const USER = "asherbarr";
const DISPLAY_NAME = "Osher Bar";
const START_DATE = "2026-10-17";

// tokyonight, same palette as the languages card
const COLORS = { bg: "#1a1b27", title: "#70a5fd", text: "#38bdae", icon: "#bf91f3", muted: "#a9b1d6", ring: "#70a5fd" };

async function graphql(query, variables) {
  const res = await fetch("https://api.github.com/graphql", {
    method: "POST",
    headers: { Authorization: `bearer ${process.env.GITHUB_TOKEN}`, "Content-Type": "application/json" },
    body: JSON.stringify({ query, variables }),
  });
  const json = await res.json();
  if (!res.ok || json.errors) throw new Error(JSON.stringify(json.errors || json));
  return json.data;
}

async function fetchStats() {
  const now = new Date();
  const data = await graphql(
    `query($login: String!, $from: DateTime!, $to: DateTime!) {
      user(login: $login) {
        repositories(ownerAffiliations: OWNER, isFork: false, first: 100) { nodes { stargazerCount } }
        contributionsCollection(from: $from, to: $to) {
          totalCommitContributions
          totalPullRequestContributions
          totalIssueContributions
          totalRepositoryContributions
          contributionCalendar { weeks { contributionDays { date contributionCount } } }
        }
      }
    }`,
    { login: USER, from: `${START_DATE}T00:00:00Z`, to: now.toISOString() }
  );
  const c = data.user.contributionsCollection;
  const days = c.contributionCalendar.weeks
    .flatMap((w) => w.contributionDays)
    .filter((d) => d.date >= START_DATE)
    .sort((a, b) => a.date.localeCompare(b.date));
  return {
    stars: data.user.repositories.nodes.reduce((sum, r) => sum + r.stargazerCount, 0),
    commits: c.totalCommitContributions,
    prs: c.totalPullRequestContributions,
    issues: c.totalIssueContributions,
    repos: c.totalRepositoryContributions,
    total: days.reduce((sum, d) => sum + d.contributionCount, 0),
    days,
  };
}

function streaks(days) {
  let longest = { length: 0, start: null, end: null };
  let run = { length: 0, start: null };
  for (const d of days) {
    if (d.contributionCount > 0) {
      if (run.length === 0) run.start = d.date;
      run.length++;
      if (run.length > longest.length) longest = { length: run.length, start: run.start, end: d.date };
    } else {
      run = { length: 0, start: null };
    }
  }
  // A day without contributions yet today doesn't break the current streak.
  let i = days.length - 1;
  if (i >= 0 && days[i].contributionCount === 0) i--;
  let current = { length: 0, start: null, end: i >= 0 ? days[i].date : null };
  while (i >= 0 && days[i].contributionCount > 0) {
    current.length++;
    current.start = days[i].date;
    i--;
  }
  return { current, longest };
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
function fmt(date) {
  if (!date) return "";
  const [, m, d] = date.split("-").map(Number);
  return `${MONTHS[m - 1]} ${d}`;
}
function range(start, end) {
  if (!start) return "No streak yet";
  return start === end ? fmt(start) : `${fmt(start)} - ${fmt(end)}`;
}
function esc(s) {
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

const FONT = "font-family=\"'Segoe UI', Ubuntu, 'Helvetica Neue', Sans-Serif\"";

// Octicons (MIT), 16x16
const ICONS = {
  star: "M8 .25a.75.75 0 01.673.418l1.882 3.815 4.21.612a.75.75 0 01.416 1.279l-3.046 2.97.719 4.192a.75.75 0 01-1.088.791L8 12.347l-3.766 1.98a.75.75 0 01-1.088-.79l.72-4.194L.818 6.374a.75.75 0 01.416-1.28l4.21-.611L7.327.668A.75.75 0 018 .25zm0 2.445L6.615 5.5a.75.75 0 01-.564.41l-3.097.45 2.24 2.184a.75.75 0 01.216.664l-.528 3.084 2.769-1.456a.75.75 0 01.698 0l2.77 1.456-.53-3.084a.75.75 0 01.216-.664l2.24-2.183-3.096-.45a.75.75 0 01-.564-.41L8 2.694v.001z",
  commit: "M10.5 7.75a2.5 2.5 0 11-5 0 2.5 2.5 0 015 0zm1.43.75a4.002 4.002 0 01-7.86 0H.75a.75.75 0 110-1.5h3.32a4.001 4.001 0 017.86 0h3.32a.75.75 0 110 1.5h-3.32z",
  pr: "M7.177 3.073L9.573.677A.25.25 0 0110 .854v4.792a.25.25 0 01-.427.177L7.177 3.427a.25.25 0 010-.354zM3.75 2.5a.75.75 0 100 1.5.75.75 0 000-1.5zm-2.25.75a2.25 2.25 0 113 2.122v5.256a2.251 2.251 0 11-1.5 0V5.372A2.25 2.25 0 011.5 3.25zM11 2.5h-1V4h1a1 1 0 011 1v5.628a2.251 2.251 0 101.5 0V5A2.5 2.5 0 0011 2.5zm1 10.25a.75.75 0 111.5 0 .75.75 0 01-1.5 0zM3.75 12a.75.75 0 100 1.5.75.75 0 000-1.5z",
  issue: "M8 9.5a1.5 1.5 0 100-3 1.5 1.5 0 000 3zM8 0a8 8 0 100 16A8 8 0 008 0zM1.5 8a6.5 6.5 0 1113 0 6.5 6.5 0 01-13 0z",
  repo: "M2 2.5A2.5 2.5 0 014.5 0h8.75a.75.75 0 01.75.75v12.5a.75.75 0 01-.75.75h-2.5a.75.75 0 110-1.5h1.75v-2h-8a1 1 0 00-.714 1.7.75.75 0 01-1.072 1.05A2.495 2.495 0 012 11.5v-9zm10.5-1V9h-8c-.356 0-.694.074-1 .208V2.5a1 1 0 011-1h8zM5 12.25v3.25a.25.25 0 00.4.2l1.45-1.087a.25.25 0 01.3 0L8.6 15.7a.25.25 0 00.4-.2v-3.25a.25.25 0 00-.25-.25h-3.5a.25.25 0 00-.25.25z",
};

function statsCard(s) {
  const rows = [
    ["star", "Total Stars Earned:", s.stars],
    ["commit", "Commits:", s.commits],
    ["pr", "Pull Requests:", s.prs],
    ["issue", "Issues:", s.issues],
    ["repo", "Repos Created:", s.repos],
  ];
  const body = rows
    .map(
      ([icon, label, value], i) => `
    <g transform="translate(25, ${60 + i * 25})">
      <svg width="16" height="16" viewBox="0 0 16 16" y="-12"><path fill="${COLORS.icon}" fill-rule="evenodd" d="${ICONS[icon]}"/></svg>
      <text x="25" ${FONT} font-size="14" font-weight="600" fill="${COLORS.text}">${label}</text>
      <text x="200" ${FONT} font-size="14" font-weight="700" fill="${COLORS.text}">${value}</text>
    </g>`
    )
    .join("");
  return `<svg xmlns="http://www.w3.org/2000/svg" width="467" height="195" viewBox="0 0 467 195">
  <title>${esc(DISPLAY_NAME)}'s GitHub Stats</title>
  <rect width="467" height="195" rx="4.5" fill="${COLORS.bg}"/>
  <text x="25" y="35" ${FONT} font-size="18" font-weight="600" fill="${COLORS.title}">${esc(DISPLAY_NAME)}'s GitHub Stats</text>
  ${body}
  <g transform="translate(375, 105)">
    <circle r="50" fill="none" stroke="${COLORS.ring}" stroke-opacity="0.2" stroke-width="6"/>
    <text text-anchor="middle" y="6" ${FONT} font-size="28" font-weight="700" fill="${COLORS.title}">${s.total}</text>
    <text text-anchor="middle" y="28" ${FONT} font-size="11" fill="${COLORS.muted}">contributions</text>
  </g>
</svg>
`;
}

function streakCard(s) {
  const { current, longest } = streaks(s.days);
  const col = (x, value, label, sub, color, ring) => `
  <g transform="translate(${x}, 0)">
    ${ring ? `<circle cx="0" cy="71" r="40" fill="none" stroke="${COLORS.ring}" stroke-width="5"/>` : ""}
    <text text-anchor="middle" y="${ring ? 80 : 79}" ${FONT} font-size="28" font-weight="700" fill="${color}">${value}</text>
    <text text-anchor="middle" y="${ring ? 140 : 125}" ${FONT} font-size="14" font-weight="${ring ? 700 : 400}" fill="${ring ? COLORS.icon : COLORS.title}">${label}</text>
    <text text-anchor="middle" y="${ring ? 162 : 152}" ${FONT} font-size="12" fill="${COLORS.text}">${sub}</text>
  </g>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="495" height="195" viewBox="0 0 495 195">
  <title>${esc(DISPLAY_NAME)}'s GitHub Streak</title>
  <rect width="495" height="195" rx="4.5" fill="${COLORS.bg}"/>
  <line x1="165" y1="28" x2="165" y2="170" stroke="${COLORS.muted}" stroke-opacity="0.4" stroke-width="1"/>
  <line x1="330" y1="28" x2="330" y2="170" stroke="${COLORS.muted}" stroke-opacity="0.4" stroke-width="1"/>
  ${col(82.5, s.total, "Total Contributions", `${fmt(START_DATE)} - Present`, COLORS.title, false)}
  ${col(247.5, current.length, "Current Streak", range(current.start, current.end), COLORS.icon, true)}
  ${col(412.5, longest.length, "Longest Streak", range(longest.start, longest.end), COLORS.title, false)}
</svg>
`;
}

(async () => {
  const out = process.argv[2] || "cards";
  fs.mkdirSync(out, { recursive: true });
  const stats = await fetchStats();
  fs.writeFileSync(path.join(out, "stats.svg"), statsCard(stats));
  fs.writeFileSync(path.join(out, "streak.svg"), streakCard(stats));
  const { current, longest } = streaks(stats.days);
  console.log(`total=${stats.total} commits=${stats.commits} prs=${stats.prs} issues=${stats.issues} repos=${stats.repos} stars=${stats.stars} current=${current.length} longest=${longest.length}`);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
