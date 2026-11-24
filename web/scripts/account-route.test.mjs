import assert from 'node:assert/strict';
import test from 'node:test';
import { accountSegment, parseAccountType, searchAccountSegment } from '../src/lib/deeplink/accountRoute.ts';

test('web account routes round-trip every account type and escaped keys', () => {
	for (const account of [
		{ type: 'Guest' },
		{ type: 'GuestHost', host: 'social.example' },
		{ type: 'GuestPlugin', platformId: 'Manga', host: 'reader.example:8443' },
		{ type: 'GuestPlugin', platformId: 'manga.example', host: 'api.manga.example' },
		{ type: 'Specific', accountKey: { id: 'guest-plugin', host: 'social.example' } },
		{ type: 'Specific', accountKey: { id: 'one@two,three\\four', host: 'social.example' } },
	]) {
		assert.deepEqual(parseAccountType(accountSegment(account)), account);
	}
	assert.deepEqual(parseAccountType(null), { type: 'Guest' });
});

test('search selection and bookmarked plugin links use the same account', () => {
	const segment = searchAccountSegment({ id: '__guest_plugin__:Manga', host: 'reader.example' });
	assert.equal(segment, 'guest-plugin@Manga@reader.example');
	assert.deepEqual(parseAccountType(segment), { type: 'GuestPlugin', platformId: 'Manga', host: 'reader.example' });
	assert.equal(searchAccountSegment({ id: 'one', host: 'social.example' }), 'one@social.example');
});
