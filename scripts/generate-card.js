// Builds the profile's combined card: GitHub stats, top languages and streak in one SVG.
// Usage: GITHUB_TOKEN=... node scripts/generate-card.js <output-dir>
const fs = require("fs");
const path = require("path");

const USER = "asherbarr";
const TITLE = "Osher Bar's GitHub";

// tokyonight
const C = { bg: "#1a1b27", title: "#70a5fd", text: "#38bdae", icon: "#bf91f3", muted: "#a9b1d6", line: "#414868" };
const FONT = "font-family=\"'Segoe UI', Ubuntu, 'Helvetica Neue', Sans-Serif\"";

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

async function fetchData() {
  const base = await graphql(
    `query($login: String!) {
      user(login: $login) {
        createdAt
        repositories(ownerAffiliations: OWNER, isFork: false, first: 100) {
          nodes {
            stargazerCount
            languages(first: 20, orderBy: { field: SIZE, direction: DESC }) { edges { size node { name color } } }
          }
        }
      }
    }`,
    { login: USER }
  );
  const user = base.user;

  // contributionsCollection covers at most one year per query.
  const totals = { commits: 0, prs: 0, issues: 0, repos: 0 };
  const days = [];
  const now = new Date();
  for (let from = new Date(user.createdAt); from < now; ) {
    const to = new Date(Math.min(now.getTime(), from.getTime() + 365 * 24 * 3600 * 1000 - 1000));
    const data = await graphql(
      `query($login: String!, $from: DateTime!, $to: DateTime!) {
        user(login: $login) {
          contributionsCollection(from: $from, to: $to) {
            totalCommitContributions
            totalPullRequestContributions
            totalIssueContributions
            totalRepositoryContributions
            contributionCalendar { weeks { contributionDays { date contributionCount } } }
          }
        }
      }`,
      { login: USER, from: from.toISOString(), to: to.toISOString() }
    );
    const c = data.user.contributionsCollection;
    totals.commits += c.totalCommitContributions;
    totals.prs += c.totalPullRequestContributions;
    totals.issues += c.totalIssueContributions;
    totals.repos += c.totalRepositoryContributions;
    for (const w of c.contributionCalendar.weeks) days.push(...w.contributionDays);
    from = new Date(to.getTime() + 1000);
  }
  const seen = new Set();
  const uniqueDays = days
    .filter((d) => !seen.has(d.date) && seen.add(d.date))
    .sort((a, b) => a.date.localeCompare(b.date));
  const firstDay = user.createdAt.slice(0, 10);

  const langs = {};
  for (const repo of user.repositories.nodes) {
    for (const e of repo.languages.edges) {
      langs[e.node.name] = langs[e.node.name] || { size: 0, color: e.node.color || C.muted };
      langs[e.node.name].size += e.size;
    }
  }
  const langTotal = Object.values(langs).reduce((s, l) => s + l.size, 0) || 1;
  const languages = Object.entries(langs)
    .map(([name, l]) => ({ name, color: l.color, pct: (l.size / langTotal) * 100 }))
    .sort((a, b) => b.pct - a.pct)
    .slice(0, 5);

  const activeDays = uniqueDays.filter((d) => d.date >= firstDay);
  return {
    stars: user.repositories.nodes.reduce((s, r) => s + r.stargazerCount, 0),
    ...totals,
    total: activeDays.reduce((s, d) => s + d.contributionCount, 0),
    firstDay,
    days: activeDays,
    languages,
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
  // No contributions yet today doesn't break the current streak.
  let i = days.length - 1;
  if (i >= 0 && days[i].contributionCount === 0) i--;
  const current = { length: 0, start: null, end: i >= 0 ? days[i].date : null };
  while (i >= 0 && days[i].contributionCount > 0) {
    current.length++;
    current.start = days[i].date;
    i--;
  }
  return { current, longest };
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const fmt = (date) => (date ? `${MONTHS[Number(date.slice(5, 7)) - 1]} ${Number(date.slice(8, 10))}` : "");
const span = (s) => (!s.start ? "—" : s.start === s.end ? fmt(s.start) : `${fmt(s.start)} - ${fmt(s.end)}`);
const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

// Octicons (MIT), 16x16
const ICONS = {
  star: "M8 .25a.75.75 0 01.673.418l1.882 3.815 4.21.612a.75.75 0 01.416 1.279l-3.046 2.97.719 4.192a.75.75 0 01-1.088.791L8 12.347l-3.766 1.98a.75.75 0 01-1.088-.79l.72-4.194L.818 6.374a.75.75 0 01.416-1.28l4.21-.611L7.327.668A.75.75 0 018 .25zm0 2.445L6.615 5.5a.75.75 0 01-.564.41l-3.097.45 2.24 2.184a.75.75 0 01.216.664l-.528 3.084 2.769-1.456a.75.75 0 01.698 0l2.77 1.456-.53-3.084a.75.75 0 01.216-.664l2.24-2.183-3.096-.45a.75.75 0 01-.564-.41L8 2.694v.001z",
  commit: "M10.5 7.75a2.5 2.5 0 11-5 0 2.5 2.5 0 015 0zm1.43.75a4.002 4.002 0 01-7.86 0H.75a.75.75 0 110-1.5h3.32a4.001 4.001 0 017.86 0h3.32a.75.75 0 110 1.5h-3.32z",
  pr: "M7.177 3.073L9.573.677A.25.25 0 0110 .854v4.792a.25.25 0 01-.427.177L7.177 3.427a.25.25 0 010-.354zM3.75 2.5a.75.75 0 100 1.5.75.75 0 000-1.5zm-2.25.75a2.25 2.25 0 113 2.122v5.256a2.251 2.251 0 11-1.5 0V5.372A2.25 2.25 0 011.5 3.25zM11 2.5h-1V4h1a1 1 0 011 1v5.628a2.251 2.251 0 101.5 0V5A2.5 2.5 0 0011 2.5zm1 10.25a.75.75 0 111.5 0 .75.75 0 01-1.5 0zM3.75 12a.75.75 0 100 1.5.75.75 0 000-1.5z",
  issue: "M8 9.5a1.5 1.5 0 100-3 1.5 1.5 0 000 3zM8 0a8 8 0 100 16A8 8 0 008 0zM1.5 8a6.5 6.5 0 1113 0 6.5 6.5 0 01-13 0z",
  repo: "M2 2.5A2.5 2.5 0 014.5 0h8.75a.75.75 0 01.75.75v12.5a.75.75 0 01-.75.75h-2.5a.75.75 0 110-1.5h1.75v-2h-8a1 1 0 00-.714 1.7.75.75 0 01-1.072 1.05A2.495 2.495 0 012 11.5v-9zm10.5-1V9h-8c-.356 0-.694.074-1 .208V2.5a1 1 0 011-1h8zM5 12.25v3.25a.25.25 0 00.4.2l1.45-1.087a.25.25 0 01.3 0L8.6 15.7a.25.25 0 00.4-.2v-3.25a.25.25 0 00-.25-.25h-3.5a.25.25 0 00-.25.25z",
  flame: "M7.998 14.5c2.832 0 5-1.98 5-4.5 0-1.463-.68-2.19-1.879-3.383l-.036-.037c-1.013-1.008-2.3-2.29-2.834-4.434-.322.256-.63.579-.864.953-.432.696-.621 1.58-.046 2.73.473.947.67 2.284-.278 3.232-.61.61-1.545.84-2.403.633a2.788 2.788 0 01-1.436-.874A3.21 3.21 0 003 10c0 2.53 2.164 4.5 4.998 4.5zM9.533.753C9.496.34 9.16.009 8.77.146 7.035.75 4.34 3.187 5.997 6.5c.344.689.285 1.218.003 1.5-.419.419-1.54.487-2.04-.832-.173-.454-.659-.762-1.035-.454C2.036 7.44 1.5 8.702 1.5 10c0 3.512 2.998 6 6.498 6s6.5-2.5 6.5-6c0-2.137-1.128-3.26-2.312-4.438-1.19-1.184-2.436-2.425-2.653-4.81z",
  calendar: "M4.75 0a.75.75 0 01.75.75V2h5V.75a.75.75 0 011.5 0V2h1.25c.966 0 1.75.784 1.75 1.75v10.5A1.75 1.75 0 0113.25 16H2.75A1.75 1.75 0 011 14.25V3.75C1 2.784 1.784 2 2.75 2H4V.75A.75.75 0 014.75 0zm0 3.5h8.5a.25.25 0 01.25.25V6h-11V3.75a.25.25 0 01.25-.25h2zm-2.25 4v6.75c0 .138.112.25.25.25h10.5a.25.25 0 00.25-.25V7.5h-11z",
  trophy: "M3.217 6.962A3.75 3.75 0 010 3.25v-.5C0 1.784.784 1 1.75 1h1.356c.228-.585.796-1 1.462-1h6.864c.647 0 1.227.395 1.462 1h1.356c.966 0 1.75.784 1.75 1.75v.5a3.75 3.75 0 01-3.217 3.712 5.014 5.014 0 01-2.771 3.117l.144 1.446c.005.05.03.12.114.204.086.087.217.17.373.227.283.103.618.255.89.553.284.31.469.743.469 1.311v.5a.75.75 0 01-.75.75h-6.5a.75.75 0 01-.75-.75v-.5c0-.568.185-1 .469-1.311.272-.298.607-.45.89-.553a.996.996 0 00.373-.227c.084-.084.109-.155.114-.204l.144-1.446a5.014 5.014 0 01-2.771-3.117zM4.5 1.568V5.5a3.5 3.5 0 107 0V1.568a.068.068 0 00-.068-.068H4.568a.068.068 0 00-.068.068z",
};

function row(x, y, icon, label, value, sub) {
  return `
    <g transform="translate(${x}, ${y})">
      <svg width="16" height="16" viewBox="0 0 16 16" y="-13"><path fill="${C.icon}" fill-rule="evenodd" d="${ICONS[icon]}"/></svg>
      <text x="26" ${FONT} font-size="14" font-weight="600" fill="${C.text}">${esc(label)}</text>
      <text x="205" ${FONT} font-size="14" font-weight="700" fill="${C.title}">${esc(value)}</text>${
        sub ? `\n      <text x="26" y="18" ${FONT} font-size="11" fill="${C.muted}">${esc(sub)}</text>` : ""
      }
    </g>`;
}

function card(d) {
  const W = 900, H = 210, COL = W / 3;
  const { current, longest } = streaks(d.days);

  const stats = [
    ["star", "Stars Earned:", d.stars],
    ["commit", "Commits:", d.commits],
    ["pr", "Pull Requests:", d.prs],
    ["issue", "Issues:", d.issues],
    ["repo", "Repos Created:", d.repos],
  ].map(([i, l, v], n) => row(25, 72 + n * 27, i, l, v)).join("");

  // Languages: stacked bar + list
  const barX = COL + 25, barW = COL - 50;
  let offset = 0;
  const bar = d.languages
    .map((l) => {
      const w = Math.max((l.pct / 100) * barW, 1.5);
      const seg = `<rect x="${(barX + offset).toFixed(2)}" y="62" width="${w.toFixed(2)}" height="8" fill="${l.color}"/>`;
      offset += w;
      return seg;
    })
    .join("");
  const langList = d.languages
    .map(
      (l, n) => `
    <g transform="translate(${barX}, ${96 + n * 22})">
      <circle cx="5" cy="-4" r="5" fill="${l.color}"/>
      <text x="18" ${FONT} font-size="13" fill="${C.text}">${esc(l.name)}</text>
      <text x="${barW}" text-anchor="end" ${FONT} font-size="13" font-weight="600" fill="${C.title}">${l.pct.toFixed(1)}%</text>
    </g>`
    )
    .join("");

  const streak = [
    row(COL * 2 + 25, 72, "calendar", "Contributions:", d.total, `${fmt(d.firstDay)} - Present`),
    row(COL * 2 + 25, 126, "flame", "Current Streak:", current.length, span(current)),
    row(COL * 2 + 25, 180, "trophy", "Longest Streak:", longest.length, span(longest)),
  ].join("");

  const heading = (x, text) => `<text x="${x}" y="38" ${FONT} font-size="17" font-weight="600" fill="${C.title}">${text}</text>`;

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">
  <title>${esc(TITLE)}</title>
  <rect width="${W}" height="${H}" rx="6" fill="${C.bg}"/>
  ${heading(25, "GitHub Stats")}
  ${heading(COL + 25, "Top Languages")}
  ${heading(COL * 2 + 25, "Streak")}
  <line x1="${COL}" y1="25" x2="${COL}" y2="${H - 25}" stroke="${C.line}" stroke-width="1"/>
  <line x1="${COL * 2}" y1="25" x2="${COL * 2}" y2="${H - 25}" stroke="${C.line}" stroke-width="1"/>
  ${stats}
  <clipPath id="bar"><rect x="${barX}" y="62" width="${barW}" height="8" rx="4"/></clipPath>
  <g clip-path="url(#bar)">${bar}</g>
  ${langList}
  ${streak}
</svg>
`;
}

(async () => {
  const out = process.argv[2] || "out";
  fs.mkdirSync(out, { recursive: true });
  const data = await fetchData();
  fs.writeFileSync(path.join(out, "github-card.svg"), card(data));
  const { current, longest } = streaks(data.days);
  console.log(
    `total=${data.total} commits=${data.commits} prs=${data.prs} issues=${data.issues} repos=${data.repos} stars=${data.stars} ` +
      `current=${current.length} longest=${longest.length} langs=${data.languages.map((l) => `${l.name}:${l.pct.toFixed(1)}`).join(",")}`
  );
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
