#!/usr/bin/env node
const fs = require('fs');
const path = require('path');

function usage() {
  console.error('Usage: node scripts/render-report.js <input.json> [output.html]');
  process.exit(1);
}

const [, , inputArg, outputArg] = process.argv;

if (!inputArg) {
  usage();
}

const inputPath = path.resolve(process.cwd(), inputArg);
const outputPath = outputArg
  ? path.resolve(process.cwd(), outputArg)
  : `${inputPath.replace(/\.json$/i, '')}.html`;

const raw = fs.readFileSync(inputPath, 'utf8');
const report = JSON.parse(raw);

const aggregate = report.aggregate || {};
const counters = aggregate.counters || {};
const summaries = aggregate.summaries || {};

const numberFormatter = new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 });

function formatNumber(value) {
  if (typeof value !== 'number' || Number.isNaN(value)) return '-';
  return numberFormatter.format(value);
}

function renderCountersTable() {
  const rows = Object.entries(counters)
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([name, value]) => `<tr><td>${name}</td><td>${formatNumber(value)}</td></tr>`)
    .join('\n');
  return `<table>
    <thead>
      <tr><th>Counter</th><th>Value</th></tr>
    </thead>
    <tbody>
      ${rows || '<tr><td colspan="2">No counters recorded.</td></tr>'}
    </tbody>
  </table>`;
}

function renderSummaryTables() {
  const entries = Object.entries(summaries);
  if (!entries.length) {
    return '<p>No latency summaries recorded.</p>';
  }
  return entries
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([metric, values]) => {
      const cells = ['min', 'max', 'p50', 'p75', 'p90', 'p95', 'p99'].map(
        (label) => `<td>${formatNumber(values[label])}</td>`,
      );
      return `<tr><td>${metric}</td>${cells.join('')}</tr>`;
    })
    .join('\n');
}

const html = `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <title>Artillery Report</title>
    <style>
      body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin: 24px; background: #f7f7f8; color: #1f2328; }
      header { margin-bottom: 24px; }
      h1 { margin: 0 0 8px; }
      .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 24px; }
      section { background: #fff; border-radius: 12px; box-shadow: 0 2px 6px rgba(0,0,0,0.06); padding: 20px; }
      table { width: 100%; border-collapse: collapse; font-size: 14px; }
      th, td { padding: 6px 8px; border-bottom: 1px solid #e6e8eb; text-align: left; }
      th { background: #f0f2f5; font-weight: 600; }
      tr:last-child td { border-bottom: none; }
      code { font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace; }
    </style>
  </head>
  <body>
    <header>
      <h1>Artillery 요약 보고서</h1>
      <p>원본 JSON: <code>${path.basename(inputPath)}</code></p>
    </header>
    <div class="grid">
      <section>
        <h2>카운터</h2>
        ${renderCountersTable()}
      </section>
      <section>
        <h2>지연 통계 (ms)</h2>
        <table>
          <thead>
            <tr>
              <th>Metric</th>
              <th>min</th>
              <th>max</th>
              <th>p50</th>
              <th>p75</th>
              <th>p90</th>
              <th>p95</th>
              <th>p99</th>
            </tr>
          </thead>
          <tbody>
            ${renderSummaryTables()}
          </tbody>
        </table>
      </section>
    </div>
  </body>
</html>`;

fs.writeFileSync(outputPath, html, 'utf8');
console.log(`Wrote dashboard to ${outputPath}`);
