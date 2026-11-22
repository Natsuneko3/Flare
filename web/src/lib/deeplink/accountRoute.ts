import type { AccountType, MicroBlogKey } from '@flare/web-presenters/search.svelte';

export function accountSegment(account: AccountType): string {
	switch (account.type) {
		case 'Specific':
			return accountSegmentFromKey(account.accountKey);
		case 'GuestHost':
			return `guest@${account.host}`;
		case 'GuestPlugin':
			return `guest-plugin@${account.platformId}@${account.host}`;
		case 'Guest':
			return 'guest';
	}
}

export function parseAccountType(value: string | null): AccountType {
	if (!value || value === 'guest') return { type: 'Guest' };
	if (value.startsWith('guest@')) return { type: 'GuestHost', host: value.slice('guest@'.length) };
	const plugin = /^guest-plugin@([^@\\]+)@(.+)$/.exec(value);
	if (plugin) return { type: 'GuestPlugin', platformId: plugin[1], host: plugin[2] };
	return { type: 'Specific', accountKey: parseMicroBlogKey(value) };
}

export function searchAccountSegment(key: MicroBlogKey): string {
	const guestPrefix = '__guest_plugin__:';
	if (key.host && key.id.startsWith(guestPrefix) && key.id.length > guestPrefix.length) {
		return accountSegment({ type: 'GuestPlugin', platformId: key.id.slice(guestPrefix.length), host: key.host });
	}
	return accountSegmentFromKey(key);
}

export function parseMicroBlogKey(value: string): MicroBlogKey {
	let escaping = false;
	let idFinished = false;
	let id = '';
	let host = '';

	for (const char of value) {
		if (escaping) {
			if (idFinished) host += char;
			else id += char;
			escaping = false;
			continue;
		}

		if (char === '\\') {
			escaping = true;
			continue;
		}

		if (char === '@' && !idFinished) {
			idFinished = true;
			continue;
		}

		if (char === ',') break;

		if (idFinished) host += char;
		else id += char;
	}

	return { id, host };
}

function accountSegmentFromKey(key: MicroBlogKey): string {
	return key.host ? `${escapeMicroBlogKeyPart(key.id)}@${escapeMicroBlogKeyPart(key.host)}` : key.id;
}

function escapeMicroBlogKeyPart(value: string): string {
	return value.replace(/[\\@,]/g, (char) => `\\${char}`);
}
