package dev.alfieprojects.stablestill.gl

/**
 * GLSL ES 3.0 sources for the warp-and-merge pipeline.
 *
 * Fragment shaders rather than compute shaders, on purpose. Compute would let us
 * do the whole merge in one dispatch, but a gather-style fragment pass maps
 * perfectly onto what we need - one output pixel reads from one place in each
 * input - and it runs on the GLES 3.0 baseline instead of requiring 3.1.
 *
 * (RenderScript, which older write-ups still recommend for this, was deprecated
 * in Android 12 and should not be used in anything new.)
 */
object Shaders {

    /** A single oversized triangle covering the viewport; cheaper than a quad. */
    const val VERTEX = """#version 300 es
        out vec2 vUv;
        void main() {
            // gl_VertexID 0,1,2 -> (-1,-1), (3,-1), (-1,3)
            float x = float((gl_VertexID & 1) << 2) - 1.0;
            float y = float((gl_VertexID & 2) << 1) - 1.0;
            vUv = vec2((x + 1.0) * 0.5, (y + 1.0) * 0.5);
            gl_Position = vec4(x, y, 0.0, 1.0);
        }
    """

    /**
     * Warps one frame into anchor space and emits it weighted by how much it
     * agrees with the anchor.
     *
     * The weight is the whole trick. A plain average of aligned frames kills
     * noise beautifully and smears anything that moved - a passing car, leaves,
     * a person - into a ghost. Down-weighting pixels that disagree with the
     * anchor keeps the noise reduction on static areas and quietly falls back to
     * "just the anchor" wherever the scene itself changed.
     *
     * Output is premultiplied: `rgb * w` in the colour channels, `w` in alpha, so
     * additive blending accumulates both a weighted sum and its divisor.
     */
    const val WARP_AND_WEIGHT = """#version 300 es
        precision highp float;

        in vec2 vUv;
        out vec4 fragColor;

        uniform sampler2D uY;
        uniform sampler2D uU;
        uniform sampler2D uV;
        uniform sampler2D uAnchor;

        uniform mat3 uSampling;        // output pixel -> source pixel
        uniform vec2 uOutputSize;
        uniform vec2 uSourceSize;
        uniform int  uSemiPlanar;      // 1 when chroma arrives interleaved as RG
        uniform float uRejectSigma;    // luma difference at which weight falls to ~1/e
        uniform float uIsAnchor;       // 1.0 for the anchor itself, which is never rejected

        vec3 yuvToRgb(vec2 uv) {
            float y = texture(uY, uv).r;
            float cb, cr;
            if (uSemiPlanar == 1) {
                vec2 c = texture(uU, uv).rg;
                cb = c.r; cr = c.g;
            } else {
                cb = texture(uU, uv).r;
                cr = texture(uV, uv).r;
            }
            // BT.601 full-range, which is what YUV_420_888 delivers.
            cb -= 0.5; cr -= 0.5;
            return clamp(vec3(
                y + 1.402 * cr,
                y - 0.344136 * cb - 0.714136 * cr,
                y + 1.772 * cb
            ), 0.0, 1.0);
        }

        void main() {
            vec2 outPx = vUv * uOutputSize;
            vec3 mapped = uSampling * vec3(outPx, 1.0);
            if (abs(mapped.z) < 1e-6) { fragColor = vec4(0.0); return; }
            vec2 srcPx = mapped.xy / mapped.z;

            // Half a pixel of guard so bilinear taps never reach outside the frame.
            if (srcPx.x < 0.5 || srcPx.y < 0.5 ||
                srcPx.x > uSourceSize.x - 1.5 || srcPx.y > uSourceSize.y - 1.5) {
                fragColor = vec4(0.0);
                return;
            }

            vec3 rgb = yuvToRgb(srcPx / uSourceSize);

            float w = 1.0;
            if (uIsAnchor < 0.5) {
                vec3 ref = texture(uAnchor, vUv).rgb;
                float d = length(rgb - ref);
                w = exp(-(d * d) / (uRejectSigma * uRejectSigma));
            }

            fragColor = vec4(rgb * w, w);
        }
    """

    /** Renders the anchor frame alone, so later passes have something to compare against. */
    const val ANCHOR_ONLY = """#version 300 es
        precision highp float;

        in vec2 vUv;
        out vec4 fragColor;

        uniform sampler2D uY;
        uniform sampler2D uU;
        uniform sampler2D uV;
        uniform mat3 uSampling;
        uniform vec2 uOutputSize;
        uniform vec2 uSourceSize;
        uniform int uSemiPlanar;

        void main() {
            vec2 outPx = vUv * uOutputSize;
            vec3 mapped = uSampling * vec3(outPx, 1.0);
            vec2 srcPx = mapped.xy / max(abs(mapped.z), 1e-6) * sign(mapped.z);
            vec2 uv = clamp(srcPx / uSourceSize, vec2(0.0), vec2(1.0));

            float y = texture(uY, uv).r;
            float cb, cr;
            if (uSemiPlanar == 1) {
                vec2 c = texture(uU, uv).rg;
                cb = c.r; cr = c.g;
            } else {
                cb = texture(uU, uv).r;
                cr = texture(uV, uv).r;
            }
            cb -= 0.5; cr -= 0.5;
            fragColor = vec4(clamp(vec3(
                y + 1.402 * cr,
                y - 0.344136 * cb - 0.714136 * cr,
                y + 1.772 * cb
            ), 0.0, 1.0), 1.0);
        }
    """

    /**
     * Divides the accumulated weighted sum by its accumulated weight.
     *
     * Where every frame was rejected the weight collapses to the anchor's own
     * contribution, so the result degrades to "the anchor frame" rather than to
     * a black hole.
     */
    const val RESOLVE = """#version 300 es
        precision highp float;

        in vec2 vUv;
        out vec4 fragColor;

        uniform sampler2D uAccum;
        uniform sampler2D uAnchor;

        void main() {
            vec4 acc = texture(uAccum, vUv);
            if (acc.a < 1e-4) {
                fragColor = vec4(texture(uAnchor, vUv).rgb, 1.0);
            } else {
                fragColor = vec4(acc.rgb / acc.a, 1.0);
            }
        }
    """
}
