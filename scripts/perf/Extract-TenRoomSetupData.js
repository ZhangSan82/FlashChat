const fs = require('fs');

function fail(message) {
  process.stderr.write(`${message}\n`);
  process.exit(1);
}

const summaryPath = process.argv[2];
if (!summaryPath) {
  fail('summary path is required');
}

let raw;
try {
  raw = fs.readFileSync(summaryPath, 'utf8');
} catch (error) {
  fail(`failed to read summary file: ${error.message}`);
}

let summary;
try {
  summary = JSON.parse(raw);
} catch (error) {
  fail(`failed to parse summary json: ${error.message}`);
}

if (!summary || !summary.setup_data || !Array.isArray(summary.setup_data.rooms)) {
  fail('summary json does not contain setup_data.rooms');
}

process.stdout.write(JSON.stringify(summary.setup_data));
