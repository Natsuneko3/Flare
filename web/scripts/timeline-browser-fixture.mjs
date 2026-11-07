// Manual browser integration fixture: node scripts/timeline-browser-fixture.mjs
// Real TimelineList and layout helper; only presenters, environment and row contents are fixtures.
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';
const web = fileURLToPath(new URL('..', import.meta.url));
const root = path.resolve(web, '../build/performance-audit/web-browser');
await fs.mkdir(root, { recursive: true });
await fs.symlink(path.join(web, 'node_modules'), path.join(root, 'node_modules'), 'dir').catch((e) => { if (e.code !== 'EEXIST') throw e; });
let source = await fs.readFile(path.join(web, 'src/lib/components/UiTimeline/TimelineList.svelte'), 'utf8');
for (const original of ['$lib/environment/environmentSettings.svelte', '$lib/paraglide/messages.js', './post/postUtils']) source = source.replaceAll(`'${original}'`, "'./fixture.ts'");
for (const original of ['./post/PostLoadingPlaceholder.svelte', './TimelineLoadingPlaceholderList.svelte']) source = source.replaceAll(`'${original}'`, "'./Empty.svelte'");
source = source.replaceAll("'./TimelineItem.svelte'", "'./Item.svelte'");
await fs.writeFile(path.join(root, 'TimelineList.svelte'), source);
await fs.copyFile(path.join(web, 'src/lib/components/UiTimeline/timelineWindow.ts'), path.join(root, 'timelineWindow.ts'));
await fs.writeFile(path.join(root, 'fixture.ts'), `export const defaultTimelineAppearance = { timelineDisplayMode: 'Card' }; export function useEnvironmentSettings() { return { timelineAppearance() { return { type: 'Success', data: defaultTimelineAppearance }; } }; } export const m = new Proxy({}, { get: (_, key) => () => key });`);
await fs.writeFile(path.join(root, 'Empty.svelte'), '<p>Loading</p>');
await fs.writeFile(path.join(root, 'Item.svelte'), `<script>let { item } = $props();</script><div data-id={item.itemKey} style:height={item.height + 'px'} style="box-sizing:border-box;padding:16px;border:1px solid #aaa;background:white"><button>Post {item.itemKey}</button><p>Variable height {item.height}px</p></div>`);
await fs.writeFile(path.join(root, 'App.svelte'), `<script>
 import TimelineList from './TimelineList.svelte';
 import { tick } from 'svelte';
 let items = $state(Array.from({length:10000}, (_, i) => ({ type:'Post', itemKey:'post:'+i, height:100 + i % 4 * 70 })));
 let sourceKey = $state('home'); let generation = $state(0); let nested = $state(false); let log = $state('Ready'); let requests = [];
 let refreshing = $state(false);
 const state = $derived.by(() => { generation; const snapshot = items; return { type:'Success', itemCount:snapshot.length, isRefreshing:refreshing, appendState:{type:'NotLoading',endOfPaginationReached:true}, peek(i) { return snapshot[i] ?? null; }, get(i) { requests.push(i); }, retry() {} }; });
 const wait = async () => { await tick(); await new Promise(r => setTimeout(r, 180)); };
 function root() { return nested ? document.querySelector('.scroll-root') : document.scrollingElement; }
 function visible() { const top = nested ? document.querySelector('.scroll-root').getBoundingClientRect().top+3 : 0; const rows=[...document.querySelectorAll('article')]; const node=rows.find(n => n.getBoundingClientRect().bottom>top+1); return node ? { key:node.querySelector('[data-id]').dataset.id, y:node.getBoundingClientRect().top } : null; }
 function jump() { root().scrollTop = 160000; }
 function prepend() { items = Array.from({length:3},(_,i)=>({type:'Post',itemKey:'new:'+i+':'+generation,height:180})).concat(items); generation++; }
 function append() { items = items.concat([{type:'Post',itemKey:'tail:'+generation,height:180}]); generation++; }
 function resize() { items=items.map(item=>({...item,height:item.height+30})); generation++; }
 function changeSource() { sourceKey='source:'+generation++; items=Array.from({length:10000},(_,i)=>({type:'Post',itemKey:'other:'+i,height:140})); }
 async function run() {
  const results=[];
  const check=(ok,label)=>results.push((ok?'PASS ':'FAIL ')+label);
  window.scrollTo(0,0); root().scrollTop=0; await wait();
  check(document.querySelectorAll('article').length<30,'bounded initial DOM');
  jump(); await wait(); let before=visible();
  check(before && document.querySelectorAll('article').length<30,'bounded distant window');
  prepend(); await wait(); let after=visible();
  check(before?.key===after?.key && Math.abs(before.y-after.y)<1,'prepend keeps row and pixel position');
  before=visible(); append(); await wait(); after=visible();
  check(before?.key===after?.key && Math.abs(before.y-after.y)<1,'append keeps row and pixel position');
  const anchorIndex=items.findIndex(i=>i.itemKey===after.key);
  items=items.map((item,i)=>i<anchorIndex?{...item,height:item.height+50}:item); generation++;
  await wait(); after=visible();
  check(before?.key===after?.key && Math.abs(before.y-after.y)<1,'image resize above anchor keeps position');
  const focusButton = [...document.querySelectorAll('article button')].find(n => n.closest('article').getBoundingClientRect().bottom > (nested ? 263 : 0));
  focusButton.focus(); root().scrollTop += 20000; await wait();
  check(document.activeElement === focusButton && document.querySelectorAll('article').length<30,'focused row survives window eviction');
  focusButton.blur(); await wait();
  const priorRequests=requests.length; generation++; await wait();
  check(requests.length>priorRequests,'same-count snapshot gets paging hints');
  root().scrollTop=0; await wait(); refreshing=true; prepend(); await wait(); refreshing=false; await wait();
  check(visible()?.key.startsWith('new:'),'top refresh shows new posts');
  changeSource(); await wait();
  check(visible()?.key==='other:0','source switch resets to first row');
  check(document.querySelector('article')?.getAttribute('aria-setsize')==='10000','accessible set size');
  check(document.querySelector('article')?.getAttribute('aria-posinset')==='1','accessible row position');
  check(document.querySelectorAll('article').length<30,'DOM stays bounded after source switch');
  results.push('Rendered rows: '+document.querySelectorAll('article').length+' / '+items.length);
  results.push('Scroll root: '+(nested?'nested element':'window'));
  log=results.join('\\n');
 }
</script>
<svelte:head><title>Flare timeline window integration fixture</title></svelte:head>
<header><button onclick={run}>Run checks</button> <button onclick={jump}>Jump far</button> <button onclick={prepend}>Prepend</button> <button onclick={append}>Append</button> <button onclick={resize}>Resize rows</button> <button onclick={changeSource}>Switch source</button> <button onclick={() => {nested=!nested;}}>Toggle nested scroller</button><pre>{log}</pre></header>
{#key nested}<div class:scroll-root={nested}><TimelineList listState={state} {sourceKey}/></div>{/key}
<style>:global(body){margin:0;background:#eee;color:#222;font:14px system-ui}header{position:fixed;top:0;right:0;z-index:1000;max-width:480px;padding:10px;background:#d5efff;box-shadow:0 2px 5px #777}pre{white-space:pre-wrap}.scroll-root{height:600px;overflow-y:auto;margin-top:260px;border:3px solid blue}</style>`);
await fs.writeFile(path.join(root, 'main.js'), "import { mount } from 'svelte'; import App from './App.svelte'; mount(App,{target:document.body});");
await fs.writeFile(path.join(root, 'index.html'), '<!doctype html><html><body><script type="module" src="/main.js"></script></body></html>');
const server = await createServer({configFile:false,root,plugins:[svelte({compilerOptions:{runes:true}})],server:{host:'127.0.0.1',port:5187,strictPort:true,fs:{allow:[path.resolve(web,'..')]}}});
await server.listen(); server.printUrls();
