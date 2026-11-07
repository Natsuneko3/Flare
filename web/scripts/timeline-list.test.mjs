import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { before, after, test } from 'node:test';
import { compile } from 'svelte/compiler';
import { render } from 'svelte/server';

let directory;
let TimelineList;
let compileComponent;
before(async () => {
	directory = await fs.mkdtemp(path.join(os.tmpdir(), 'flare-timeline-list-'));
	async function component(source, name) {
		const { js } = compile(source, { filename: `${name}.svelte`, generate: 'server' });
		const code = js.code.replaceAll("'svelte/internal/server'", JSON.stringify(import.meta.resolve('svelte/internal/server')))
			.replaceAll("'svelte'", JSON.stringify(import.meta.resolve('svelte')));
		await fs.writeFile(path.join(directory, `${name}.mjs`), code);
	}
	compileComponent = component;
	let source = await fs.readFile(new URL('../src/lib/components/UiTimeline/TimelineList.svelte', import.meta.url), 'utf8');
	for (const original of ['$lib/environment/environmentSettings.svelte', '$lib/paraglide/messages.js', './post/postUtils']) source = source.replaceAll(`'${original}'`, "'./fixture.mjs'");
	for (const original of ['./post/PostLoadingPlaceholder.svelte', './TimelineLoadingPlaceholderList.svelte']) source = source.replaceAll(`'${original}'`, "'./Loading.mjs'");
	source = source.replaceAll("'./TimelineItem.svelte'", "'./Item.mjs'").replaceAll("'./timelineWindow'", "'./timelineWindow.ts'");
	await fs.copyFile(new URL('../src/lib/components/UiTimeline/timelineWindow.ts', import.meta.url), path.join(directory, 'timelineWindow.ts'));
	await component(source, 'TimelineList');
	await component('<p>Loading placeholder</p>', 'Loading');
	await component('<script>let { sourceKey, listState } = $props();</script><p data-source={sourceKey} data-list={listState.name}></p>', 'TimelineStub');
	await component('<script>let { start } = $props();</script>{@render start?.()}', 'TopBar');
	await component('<script>let { item } = $props();</script><button>Post {item.itemKey}</button>', 'Item');
	await fs.writeFile(path.join(directory, 'fixture.mjs'), `export const defaultTimelineAppearance={timelineDisplayMode:'Card'}; export function useEnvironmentSettings(){return {timelineAppearance(){return {type:'Success',data:defaultTimelineAppearance};}};} export function createLocalCacheSearchPresenter(){ return { data:{type:'Success',name:'search'}, history:{type:'Success',name:'history'}, searchUser:{type:'Empty'},userHistory:{type:'Empty'},setQuery(){} }; } export const m=new Proxy({}, {get:(_,key)=>()=>key});`);
	TimelineList = (await import(pathToFileURL(path.join(directory, 'TimelineList.mjs')))).default;
});
after(async () => { if (directory) await fs.rm(directory, { recursive: true, force: true }); });

function success(appendState = { type: 'NotLoading', endOfPaginationReached: true }) {
	return { type:'Success', itemCount:10000, isRefreshing:false, appendState,
		peek(index) { return { type:'Post', itemKey:`post:${index}` }; },
		get() { throw Error('SSR must not send paging hints'); }, retry() {},
	};
}

test('server rendering exposes a bounded initial feed with absolute accessible positions', () => {
	const { body } = render(TimelineList, { props: { listState:success(), sourceKey:'home' } });
	const count = (body.match(/<article/g) ?? []).length;
	assert.ok(count > 0 && count < 30, `rendered ${count} rows`);
	assert.match(body, /role="feed"/);
	assert.match(body, /aria-posinset="1"/);
	assert.match(body, /aria-setsize="10000"/);
	assert.match(body, /Post post:0/);
	assert.match(body, /timelineNoMorePosts/);
});

test('loading, empty, and error states retain existing visible content', () => {
	for (const [state, text] of [
		[{ type:'Loading' }, 'Loading placeholder'],
		[{ type:'Empty' }, 'timelineEmpty'],
		[{ type:'Error', message:'Offline' }, 'Offline'],
	]) {
		const { body } = render(TimelineList, { props: { listState:state } });
		assert.ok(body.includes(text));
		assert.ok(!body.includes('<article'));
	}
});

test('append loading and retry error keep their footer controls outside the row window', () => {
	const loading = render(TimelineList, { props:{listState:success({type:'Loading'})} }).body;
	assert.match(loading, /<progress/);
	const error = render(TimelineList, { props:{listState:success({type:'Error',message:'Try later'})} }).body;
	assert.match(error, /Try later/);
	assert.match(error, /<button[^>]*>actionRetry<\/button>/);
});

// Execute the route's real submit handler before rendering the list boundary.
// Draft edits must not change the submitted source key or chosen result state.
test('history draft editing preserves the submitted timeline source', async () => {
	const route = await fs.readFile(new URL('../src/routes/history/+page.svelte', import.meta.url), 'utf8');
	for (const [index, lifecycle, sourceKey, list] of [
		[0, "query = 'draft';", 'history:', 'history'],
		[1, "query = 'saved'; submitSearch({preventDefault(){}}); query = 'draft';", 'history:saved', 'search'],
		[2, "query = 'saved'; submitSearch({preventDefault(){}}); query = '';", 'history:saved', 'search'],
	]) {
		let source = route;
		for (const original of ['$lib/paraglide/messages.js', '@flare/web-presenters/localCacheSearch.svelte']) source=source.replaceAll(`'${original}'`, "'./fixture.mjs'");
		for (const original of ['$lib/components/FaIcon.svelte', '$lib/components/AppBackButton.svelte', '$lib/components/user/UserDisplay.svelte']) source=source.replaceAll(`'${original}'`, "'./Loading.mjs'");
		source=source.replaceAll("'$lib/components/AppTopBar.svelte'", "'./TopBar.mjs'").replaceAll("'$lib/components/UiTimeline/TimelineList.svelte'", "'./TimelineStub.mjs'");
		source=source.replace('</script>', `${lifecycle}\n</script>`);
		await compileComponent(source, `History${index}`);
		const History = (await import(pathToFileURL(path.join(directory, `History${index}.mjs`)))).default;
		const { body } = render(History);
		assert.ok(body.includes(`data-source="${sourceKey}"`), body);
		assert.ok(body.includes(`data-list="${list}"`), body);
	}
});
