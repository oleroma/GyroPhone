// Renders each mockup page to a PNG: node render.js <out dir> [page.html ...]
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
(async () => {
  const out = process.argv[2];
  let pages = process.argv.slice(3);
  if (!pages.length) pages = fs.readdirSync(__dirname).filter(f => f.endsWith('.html'));
  const browser = await chromium.launch();
  const page = await browser.newPage({ deviceScaleFactor: 2, viewport: { width: 2400, height: 1600 } });
  page.on('pageerror', e => console.log('page error:', e.message));
  for (const f of pages) {
    await page.goto('file://' + path.join(__dirname, f));
    await page.evaluate(() => document.fonts.ready);
    const st = await page.$('#st');
    await st.screenshot({ path: path.join(out, f.replace('.html', '.png')), omitBackground: true });
    console.log('rendered', f);
  }
  await browser.close();
})();
