/** Prefer the presenter's stable key, which includes account and notification identity. */
export function timelineItemKey(item: unknown, index: number): string {
	if (!item) return `placeholder:${index}`;
	const value = item as {
		stableItemKey?: string; itemKey?: string; type: string; url?: string;
		statusKey?: unknown; accountType?: unknown;
		post?: { statusKey: unknown; accountType: unknown };
		presentation?: { notificationKey?: unknown };
	};
	const notification = value.presentation?.notificationKey;
	const post = value.type === 'TimelinePostItem' ? value.post : value;
	return `item:${value.stableItemKey ?? value.itemKey ?? JSON.stringify([
		post?.accountType,
		notification ? 'notification' : (value.type === 'Post' || value.type === 'TimelinePostItem' ? 'post' : value.type),
		notification ?? (value.type === 'Feed' ? value.url : post?.statusKey),
	])}`;
}

type Anchor = { key: string; index: number; within: number };

/** Pure variable-height layout. Offsets include each row's visual gap. */
export class TimelineWindow {
	private keys: string[] = [];
	private indexes = new Map<string, number>();
	private heights = new Map<string, number>();
	private offsets = [0];
	private source: string | undefined;
	private estimate: number;

	constructor(estimate = 320) { this.estimate = estimate; }
	get totalHeight(): number { return this.offsets[this.keys.length]; }
	offset(index: number): number { return this.offsets[Math.max(0, Math.min(index, this.keys.length))]; }

	private rebuild() {
		this.offsets = [0];
		for (const key of this.keys) this.offsets.push(this.offsets.at(-1)! + (this.heights.get(key) ?? this.estimate));
	}
	private indexAt(top: number): number {
		let lo = 0, hi = this.keys.length;
		while (lo < hi) {
			const mid = (lo + hi) >>> 1;
			if (this.offsets[mid + 1] <= top) lo = mid + 1; else hi = mid;
		}
		return Math.min(lo, this.keys.length - 1);
	}
	private anchor(top: number): Anchor | undefined {
		if (!this.keys.length || top <= 0) return;
		const index = this.indexAt(top);
		return { key: this.keys[index], index, within: top - this.offsets[index] };
	}

	reconcile(keys: string[], scrollTop: number, source: string): number {
		const reset = this.source !== undefined && source !== this.source;
		const anchor = reset ? undefined : this.anchor(scrollTop);
		const oldKeys = this.keys;
		// A resolved placeholder is the same paging slot, not a new source.
		const resolved = new Map<string, string>();
		if (!reset) oldKeys.forEach((key, index) => {
			if (key === `placeholder:${index}` && keys[index]) {
				resolved.set(key, keys[index]);
				const height = this.heights.get(key);
				if (height !== undefined) this.heights.set(keys[index], height);
			}
		});
		this.source = source;
		this.keys = keys;
		this.indexes = new Map(keys.map((key, index) => [key, index]));
		const retained = new Set(keys);
		this.heights = reset ? new Map() : new Map([...this.heights].filter(([key]) => retained.has(key)));
		this.rebuild();
		if (reset) return Math.min(0, scrollTop);
		if (!anchor) return scrollTop;
		const index = this.indexes.get(resolved.get(anchor.key) ?? anchor.key) ?? -1;
		if (index >= 0) return this.offsets[index] + anchor.within;
		// If the anchor was removed, keep the nearest following surviving row at the top.
		for (let i = anchor.index + 1; i < oldKeys.length; i++) {
			const next = this.indexes.get(oldKeys[i]) ?? -1;
			if (next >= 0) return this.offsets[next];
		}
		for (let i = anchor.index - 1; i >= 0; i--) {
			const previous = this.indexes.get(oldKeys[i]);
			if (previous !== undefined) return this.offsets[previous] + Math.min(anchor.within, this.offsets[previous + 1] - this.offsets[previous]);
		}
		return Math.min(0, scrollTop);
	}

	measure(entries: [string, number][], scrollTop: number): number {
		const anchor = this.anchor(scrollTop);
		for (const [key, height] of entries) {
			if (this.indexes.has(key) && Number.isFinite(height) && height > 0) this.heights.set(key, height);
		}
		this.rebuild();
		return anchor ? this.offsets[anchor.index] + anchor.within : scrollTop;
	}

	invalidateMeasurements(scrollTop: number): number {
		const anchor = this.anchor(scrollTop);
		this.heights.clear();
		this.rebuild();
		return anchor ? this.offsets[anchor.index] + anchor.within : scrollTop;
	}

	range(scrollTop: number, viewport: number, overscan: number) {
		if (!this.keys.length) return { start: 0, end: 0, before: 0, after: 0 };
		const start = this.indexAt(Math.max(0, scrollTop - overscan));
		const limit = Math.max(0, scrollTop + viewport + overscan);
		let end = start + 1;
		while (end < this.keys.length && this.offsets[end] < limit) end++;
		return { start, end, before: this.offsets[start], after: this.totalHeight - this.offsets[end] };
	}
}
