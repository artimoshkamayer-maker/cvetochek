// Копирует веб-плеер в www/ и в нативные ассеты. Запуск: node tools/sync-www.mjs
import { rmSync, mkdirSync, cpSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');
const shell = (body) => `<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<title>Цветочек</title>
</head>
<body style="background:#121016">
<script>var root;</script>
${body}
</body>
</html>
`;

const body = readFileSync(join(repo, 'web-src', 'index.html'), 'utf8');
mkdirSync(join(repo, 'www', 'src'), { recursive: true });
writeFileSync(join(repo, 'www', 'index.html'), shell(body));
cpSync(join(repo, 'web-src', 'img'), join(repo, 'www', 'src', 'img'), { recursive: true });

const pub = join(repo, 'android', 'app', 'src', 'main', 'assets', 'public');
rmSync(pub, { recursive: true, force: true });
mkdirSync(pub, { recursive: true });
cpSync(join(repo, 'www'), pub, { recursive: true });
cpSync(
  join(repo, 'capacitor.config.json'),
  join(repo, 'android', 'app', 'src', 'main', 'assets', 'capacitor.config.json')
);
console.log('sync ok: www + android assets');
