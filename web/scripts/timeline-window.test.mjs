import assert from 'node:assert/strict';
import { test } from 'node:test';
import { TimelineWindow, timelineItemKey } from '../src/lib/components/UiTimeline/timelineWindow.ts';

// Catches eager rendering and off-by-one rows at viewport boundaries.
test('bounds a long timeline to the viewport and pixel overscan', () => {
	const model = new TimelineWindow(100);
	model.reconcile(Array.from({ length: 10000 }, (_, i) => `${i}`), 0, 'home');
	assert.deepEqual(model.range(5000, 500, 200), { start: 48, end: 57, before: 4800, after: 994300 });
	assert.deepEqual(model.range(0, 200, 0), { start: 0, end: 2, before: 0, after: 999800 });
});

test('empty and out-of-bounds viewports produce safe ranges', () => {
	const model = new TimelineWindow(100);
	assert.deepEqual(model.range(0, 800, 200), { start: 0, end: 0, before: 0, after: 0 });
	model.reconcile(['a', 'b'], 0, 'home');
	assert.deepEqual(model.range(500, 100, 0), { start: 1, end: 2, before: 100, after: 0 });
});

test('actual variable heights determine visible ranges and spacer totals', () => {
	const model = new TimelineWindow(100);
	model.reconcile(['a', 'b', 'c', 'd'], 0, 'home');
	model.measure([['a', 50], ['b', 200], ['c', 80]], 0);
	assert.deepEqual(model.range(60, 200, 0), { start: 1, end: 3, before: 50, after: 100 });
	assert.equal(model.totalHeight, 430);
});

test('height changes above the reader preserve the row and within-row offset', () => {
	const model = new TimelineWindow(100);
	model.reconcile(['a', 'b', 'c', 'd'], 0, 'home');
	assert.equal(model.measure([['a', 250], ['c', 350]], 125), 275);
	// Resizing below the anchored row cannot move the viewport.
	assert.equal(model.measure([['d', 800]], 275), 275);
});

test('append preserves reading position and prepend follows the same identity', () => {
	const model = new TimelineWindow(100);
	model.reconcile(['a', 'b', 'c'], 0, 'home');
	model.measure([['a', 50], ['b', 200]], 0);
	assert.equal(model.reconcile(['a', 'b', 'c', 'd'], 80, 'home'), 80);
	assert.equal(model.reconcile(['x', 'y', 'a', 'b', 'c', 'd'], 80, 'home'), 280);
	assert.equal(model.offset(3), 250);
});

test('same-count replacement and source switch cannot reuse stale heights', () => {
	const model = new TimelineWindow(100);
	model.reconcile(['a', 'b', 'c'], 0, 'home');
	model.measure([['a', 300]], 0);
	assert.equal(model.reconcile(['x', 'y', 'z'], 350, 'home'), 0);
	assert.equal(model.totalHeight, 300);
	model.measure([['x', 450]], 0);
	assert.equal(model.reconcile(['x', 'y', 'z'], 470, 'profile'), 0);
	assert.equal(model.totalHeight, 300);
});

test('reorder retains identity heights and removed anchor uses a surviving neighbor', () => {
	const model = new TimelineWindow(100);
	model.reconcile(['a', 'b', 'c', 'd'], 0, 'home');
	model.measure([['b', 200]], 0);
	assert.equal(model.reconcile(['c', 'a', 'b', 'd'], 130, 'home'), 230);
	assert.equal(model.reconcile(['c', 'a', 'd'], 230, 'home'), 200);
});

test('identity distinguishes notification/post/account and tolerates placeholders', () => {
	assert.equal(timelineItemKey({ type: 'Post', stableItemKey: 'stable', statusKey: { id: '1', host: 'a' } }, 1), 'item:stable');
	assert.equal(timelineItemKey(null, 5), 'placeholder:5');
	assert.notEqual(timelineItemKey({ itemKey: 'placeholder:5', type: 'Post' }, 5), timelineItemKey(null, 5));
	const post = { type: 'Post', statusKey: { id: '1', host: 'a' }, accountType: { type: 'Guest' } };
	assert.notEqual(timelineItemKey(post, 0), timelineItemKey({ ...post, accountType: { type: 'Specific', accountKey: { id: 'me', host: 'a' } } }, 0));
	assert.notEqual(timelineItemKey(post, 0), timelineItemKey({ ...post, type: 'TimelinePostItem', presentation: { notificationKey: { id: '2', host: 'a' } } }, 0));
});

test('refresh at the top shows new posts and positions above the list stay above it', () => {
	const model = new TimelineWindow(100);
	model.reconcile(['a', 'b'], -300, 'home');
	assert.equal(model.reconcile(['x', 'a', 'b'], 0, 'home'), 0);
	assert.equal(model.measure([['x', 500]], -200), -200);
});

test('loading placeholders retain measured heights and reading position when resolved', () => {
	const model = new TimelineWindow(100);
	model.reconcile(['placeholder:0', 'placeholder:1', 'c'], 0, 'home');
	model.measure([['placeholder:0', 150]], 0);
	assert.equal(model.reconcile(['a', 'b', 'c'], 175, 'home'), 175);
	assert.equal(model.offset(1), 150);
});

test('width changes invalidate old measurements while keeping the same reading anchor', () => {
	const model = new TimelineWindow(100);
	model.reconcile(['a', 'b', 'c'], 0, 'home');
	model.measure([['a', 250], ['b', 200]], 0);
	assert.equal(model.invalidateMeasurements(280), 130);
	assert.equal(model.totalHeight, 300);
});

test('removing the final anchored row falls back to its preceding neighbor', () => {
	const model = new TimelineWindow(100);
	model.reconcile(['a', 'b', 'c'], 0, 'home');
	assert.equal(model.reconcile(['a', 'b'], 240, 'home'), 140);
});

test('generated presenter shapes use nested post keys and feed URLs for identity', () => {
	const post = { type: 'TimelinePostItem', itemKey: null, post: { accountType: { type: 'Guest' }, statusKey: { id: '1', host: 'a' } }, presentation: { notificationKey: null } };
	assert.notEqual(timelineItemKey(post, 0), timelineItemKey({ ...post, post: { ...post.post, statusKey: { id: '2', host: 'a' } } }, 0));
	assert.notEqual(timelineItemKey(post, 0), timelineItemKey({ ...post, post: { ...post.post, accountType: { type: 'Specific', accountKey: { id: 'me', host: 'a' } } } }, 0));
	const feed = { type: 'Feed', itemKey: null, accountType: { type: 'Guest' }, url: 'https://a/1' };
	assert.notEqual(timelineItemKey(feed, 0), timelineItemKey({ ...feed, url: 'https://a/2' }, 0));
});
