/**
 * Local simulation server for testing DanceBook splash page behaviors.
 *
 * Usage:
 *   node splash/test/simulate-server.js [mode] [port]
 *
 * Modes:
 *   instant (default) - responds to /login immediately (0ms delay)
 *   delayed           - holds /login open for 32 seconds before responding 200 OK
 *   unresponsive      - holds /login open indefinitely (times out)
 *
 * The server serves static files from splash/ at http://localhost:<port>/
 * and simulates the Cloud Run app at http://localhost:<port>/login.
 */

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const mode = process.argv[2] || 'instant';
const port = parseInt(process.argv[3] || '8080', 10);
const splashDir = path.resolve(__dirname, '..');

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json',
  '.svg': 'image/svg+xml'
};

const server = http.createServer((req, res) => {
  const parsedUrl = new URL(req.url, `http://localhost:${port}`);
  const pathname = parsedUrl.pathname;

  console.log(`[${new Date().toISOString()}] ${req.method} ${pathname}`);

  // Simulate Cloud Run /login endpoint
  if (pathname === '/login') {
    if (mode === 'instant') {
      console.log('-> Simulating instant response (200 OK)');
      res.writeHead(200, {
        'Content-Type': 'text/html',
        'Cache-Control': 'no-store'
      });
      res.end('<!DOCTYPE html><html><body><h1>DanceBook Login (Awake)</h1></body></html>');
      return;
    }

    if (mode === 'delayed') {
      console.log('-> Simulating cold start: holding connection open for 32 seconds...');
      setTimeout(() => {
        console.log('-> Container woke up! Sending 200 OK to browser');
        res.writeHead(200, {
          'Content-Type': 'text/html',
          'Cache-Control': 'no-store'
        });
        res.end('<!DOCTYPE html><html><body><h1>DanceBook Login (Awake after cold start)</h1></body></html>');
      }, 32000);
      return;
    }

    if (mode === 'unresponsive') {
      console.log('-> Simulating unresponsive server: connection kept open until timeout');
      // Intentionally do not reply
      return;
    }
  }

  // Serve static files from splash directory
  let filePath = path.join(splashDir, pathname === '/' ? 'index.html' : pathname);
  if (!fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
    // Single page rewrite fallback to index.html
    filePath = path.join(splashDir, 'index.html');
  }

  const ext = path.extname(filePath).toLowerCase();
  const contentType = MIME_TYPES[ext] || 'application/octet-stream';

  fs.readFile(filePath, (err, content) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('File Not Found');
    } else {
      res.writeHead(200, { 'Content-Type': contentType });
      res.end(content);
    }
  });
});

server.listen(port, () => {
  console.log(`=======================================================`);
  console.log(`DanceBook Splash Simulation Server`);
  console.log(`Mode:    ${mode}`);
  console.log(`Address: http://localhost:${port}/`);
  console.log(`App base: http://localhost:${port}`);
  console.log(`To test with splash pointing to this local server:`);
  console.log(`  Open in browser: http://localhost:${port}/?target=http://localhost:${port}`);
  console.log(`=======================================================`);
});
