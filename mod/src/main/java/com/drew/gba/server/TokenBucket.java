package com.drew.gba.server;

/** Simple token bucket. Not thread safe (used from the server thread). */
public final class TokenBucket {
	private final double capacity;
	private final double perNano;
	private double tokens;
	private long last;

	public TokenBucket(double capacity, double perSecond) {
		this(capacity, perSecond, System.nanoTime());
	}

	TokenBucket(double capacity, double perSecond, long startNanos) {
		this.capacity = capacity;
		this.perNano = perSecond / 1e9;
		this.tokens = capacity;
		this.last = startNanos;
	}

	public boolean take(double cost) {
		return take(cost, System.nanoTime());
	}

	public boolean take(double cost, long nowNanos) {
		tokens = Math.min(capacity, tokens + (nowNanos - last) * perNano);
		last = nowNanos;
		if (tokens < cost) return false;
		tokens -= cost;
		return true;
	}
}
