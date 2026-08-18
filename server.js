const http = require('http');
const server = http.createServer((req, res) => {
  res.writeHead(200, {'Content-Type': 'text/plain; charset=utf-8'});
  res.end('Termux Node.js Server läuft auf deinem Smartphone!\n');
});
server.listen(3000, () => {
  console.log('Server aktiv: Öffne im Browser http://localhost:3000');
});
