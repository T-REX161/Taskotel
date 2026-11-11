/**
 * Test support: renders a real .jsx component to HTML in plain Node (no browser). The component is compiled with esbuild (already installed
 * as part of Vite) into node_modules/.cache, which Git ignores; React itself is left external so the component and react-dom/server share
 * one copy of React and hooks work. Nothing here is part of the application build.
 */
import path from 'node:path';
import process from 'node:process';
import fs from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { build } from 'esbuild';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const cache = path.join(root, 'node_modules', '.cache', 'taskotel-render-tests');

const loaded = new Map();

/** The module exports of a source file under src (for example 'components/tasks/AssignTaskModal.jsx'), compiled once per test run. */
export async function loadModule(relativePath) {
  if (loaded.has(relativePath)) return loaded.get(relativePath);
  fs.mkdirSync(cache, { recursive: true });
  const outfile = path.join(cache, `${relativePath.replace(/[\\/]/g, '_')}.${process.pid}.mjs`);
  await build({
    entryPoints: [path.join(root, 'src', relativePath)],
    outfile,
    bundle: true,
    format: 'esm',
    platform: 'node',
    jsx: 'automatic',
    external: ['react', 'react/*', 'react-dom', 'react-dom/*', 'react-router-dom'],
    define: { 'import.meta.env': '{}' },
    logLevel: 'silent',
  });
  const module = await import(pathToFileURL(outfile).href);
  loaded.set(relativePath, module);
  return module;
}

/** The HTML of a component rendered with the given props. */
export function renderHtml(Component, props = {}) {
  return renderToStaticMarkup(createElement(Component, props));
}
