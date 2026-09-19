package anima.engine;

/**
 * Immutable per-frame render data produced by the animation engine and consumed by
 * renderers (GUI blits, font drawing, …). All color channels are in {@code [0,1]}.
 */
public class RenderModifier {
	public static final RenderModifier IDENTITY = builder().build();

	public final float r;
	public final float g;
	public final float b;
	public final float a;
	/** UV offset in texture pixels. */
	public final float uvU;
	public final float uvV;
	/** Translation in screen pixels. */
	public final float tx;
	public final float ty;
	/** Scale factors. */
	public final float sx;
	public final float sy;

	private RenderModifier(float r, float g, float b, float a, float uvU, float uvV, float tx, float ty, float sx, float sy) {
		this.r = r;
		this.g = g;
		this.b = b;
		this.a = a;
		this.uvU = uvU;
		this.uvV = uvV;
		this.tx = tx;
		this.ty = ty;
		this.sx = sx;
		this.sy = sy;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static Builder builder(RenderModifier base) {
		return new Builder(base);
	}

	public static final class Builder {
		private float r = 1f;
		private float g = 1f;
		private float b = 1f;
		private float a = 1f;
		private float uvU;
		private float uvV;
		private float tx;
		private float ty;
		private float sx = 1f;
		private float sy = 1f;

		public Builder() {
		}

		public Builder(RenderModifier base) {
			this.r = base.r;
			this.g = base.g;
			this.b = base.b;
			this.a = base.a;
			this.uvU = base.uvU;
			this.uvV = base.uvV;
			this.tx = base.tx;
			this.ty = base.ty;
			this.sx = base.sx;
			this.sy = base.sy;
		}

		public Builder color(float r, float g, float b) {
			this.r = r;
			this.g = g;
			this.b = b;
			return this;
		}

		public Builder alpha(float a) {
			this.a = a;
			return this;
		}

		public Builder uv(float u, float v) {
			this.uvU = u;
			this.uvV = v;
			return this;
		}

		public Builder translate(float x, float y) {
			this.tx = x;
			this.ty = y;
			return this;
		}

		public Builder scale(float sx, float sy) {
			this.sx = sx;
			this.sy = sy;
			return this;
		}

		/** Multiplies the existing alpha. */
		public Builder mulAlpha(float f) {
			this.a *= f;
			return this;
		}

		/** Multiplies the existing color channels. */
		public Builder mulColor(float fr, float fg, float fb) {
			this.r *= fr;
			this.g *= fg;
			this.b *= fb;
			return this;
		}

		public RenderModifier build() {
			return new RenderModifier(r, g, b, a, uvU, uvV, tx, ty, sx, sy);
		}
	}
}
