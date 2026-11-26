R"CLC(
#pragma OPENCL FP_CONTRACT OFF
#define PW 1824
#define PH 1368
#define W (2 * PW)
#define H (2 * PH)
#define EXT 136
#define CELLS (EXT * EXT)
#define TILE 128
#define HALO 4
#define WG 16
#define WINDOW (WG + 4)

int mirror(int p, int size) {
    return p < 0 ? -p : min(p, 2 * size - 2 - p);
}

__kernel void prepare(__global const float4* source, __global float4* input,
                      float4 slope, float4 offset, int top, int left, int external,
                      __global float4* trace, int capture) {
    int row = get_global_id(1) + top - HALO;
    int col = get_global_id(0) + left - HALO;
    int index = get_global_id(1) * EXT + get_global_id(0);
    float4 samples = 0.0f;
    float4 sigma = 0.0f, sum = 0.0f, quotient = 0.0f, variance = 0.0f;
    if (row >= 0 && row < PH && col >= 0 && col < PW) {
        int x0 = mirror(col - 1, PW);
        int x2 = mirror(col + 1, PW);
        int a = mirror(row - 1, PH) * PW;
        int b = row * PW;
        int c = mirror(row + 1, PH) * PW;
        sum = source[a+x0] + source[a+col] + source[a+x2] +
                     source[b+x0] + source[b+col] + source[b+x2] +
                     source[c+x0] + source[c+col] + source[c+x2];
#if ISP_MATH >= 1
        // Division by 9 is not correctly rounded by this driver. Use the
        // residual of the rounded quotient to correct the reciprocal product.
        const float reciprocal = 1.0f / 9.0f;
        float4 estimate = sum * reciprocal;
        quotient = estimate + fma(-estimate, (float4)9.0f, sum) * reciprocal;
#else
        quotient = sum / 9.0f;
#endif
        variance = fmax(0.0f, quotient) * slope + offset;
        sigma = sqrt(variance);
#if ISP_MATH >= 2
        // NoiseProfile in this lab produces finite, normal variances. Mask
        // zero separately so an offset of zero cannot make sigma nonfinite.
        float4 divisor = select(sigma, (float4)1.0f, sigma == 0.0f);
        sigma += fma(-sigma, sigma, variance) * (0.5f / divisor);
#endif
        samples = source[b+col];
    }
#if AUDIT_CAPTURE
    if (capture) {
        trace[3 * index] = sum;
        trace[3 * index + 1] = quotient;
        trace[3 * index + 2] = variance;
    }
#endif
    // The runtime's default packed CL buffer is contiguous NHWC. With
    // external tensors enabled, the delegate consumes PHWC4 planes directly.
    int indexOfSignal = external ? index : 2 * index;
    input[indexOfSignal] = samples * 2.0f;
    input[indexOfSignal + (external ? CELLS : 1)] = sigma * 32.0f;
}

__kernel void restore(__global const float4* source, __global const float4* input,
                      __global const float4* output, __global float4* raw,
                      volatile __global uint* invalid, int top, int left, int external,
                      __global float* trace, int capture) {
    int x = get_global_id(0);
    int y = get_global_id(1);
    int tileIndex = (y + HALO) * EXT + x + HALO;
    int index = (y + top) * PW + x + left;
    float4 samples = source[index];
    float4 residual = output[tileIndex];
    if (!all(isfinite(residual))) atomic_or(invalid, 1u);
    float4 sigma = input[external ? tileIndex + CELLS : 2 * tileIndex + 1] / 32.0f;
    float4 weight = clamp((.99f - samples) / .09f, 0.0f, 1.0f);
    float4 clamped = clamp(residual, -3.0f, 3.0f);
    float4 delta = clamped * sigma * weight;
    raw[index] = samples + delta;
#if AUDIT_CAPTURE
    if (capture) {
        int base = (y * TILE + x) * 4;
        vstore3((float3)(clamped.x, weight.x, delta.x), base, trace);
        vstore3((float3)(clamped.y, weight.y, delta.y), base + 1, trace);
        vstore3((float3)(clamped.z, weight.z, delta.z), base + 2, trace);
        vstore3((float3)(clamped.w, weight.w, delta.w), base + 3, trace);
    }
#endif
}

float linear_mix(float a, float b, float t) {
    return a + (b-a)*t;
}

float gained(__global const float* raw, __global const float* params,
             int4 offsets, int y, int x) {
    int phase = 2 * (y & 1) + (x & 1);
    int mapOffset = phase < 2 ? (phase == 0 ? offsets.s0 : offsets.s1)
                              : (phase == 2 ? offsets.s2 : offsets.s3);
    __global const float* map = params + mapOffset;
    int columns = (int)map[0];
    int rows = (int)map[1];
    __global const float* values = map + 2;
    __global const float* ay = values + columns * rows + 3 * (y / 2);
    __global const float* ax = values + columns * rows + 3 * PH + 3 * (x / 2);
    int top = (int)ay[0] * columns;
    int bottom = (int)ay[1] * columns;
    int left = (int)ax[0];
    int right = (int)ax[1];
    float a = linear_mix(values[top+left], values[top+right], ax[2]);
    float b = linear_mix(values[bottom+left], values[bottom+right], ax[2]);
    return raw[((y / 2) * PW + x / 2) * 4 + phase] * linear_mix(a, b, ay[2]);
}

#if AUDIT_CAPTURE
int display_index(int x, int y) {
    const int2 origins[] = {(int2)(0, 0), (int2)(248, 248), (int2)(1664, 1344),
                            (int2)(3632, 0), (int2)(0, 2720), (int2)(3632, 2720)};
    for (int i = 0; i < 6; ++i) {
        const int2 p = (int2)(x, y) - origins[i];
        if (all(p >= 0) && all(p < 16)) return (i * 256 + p.y * 16 + p.x) * 10;
    }
    return -1;
}
#endif

uchar encode_channel(float exposed, __global const uchar* lut) {
    float scaled = exposed * 65535.0f;
    int offset = (int)scaled;
    return lut[offset + (scaled - (float)offset >= .5f)];
}

__attribute__((reqd_work_group_size(WG, WG, 1)))
__kernel void develop(__global const float* raw, __global const float* params,
                      int4 offsets, __global const uchar* lut, __global uchar* rgb, __global float* trace) {
    __local float window[WINDOW * WINDOW];
    int lx = get_local_id(0), ly = get_local_id(1);
    int left = get_group_id(0) * WG;
    int top = get_group_id(1) * WG;
    for (int k = ly * WG + lx; k < WINDOW * WINDOW; k += WG * WG) {
        int y = mirror(top + k / WINDOW - 2, H);
        int x = mirror(left + k % WINDOW - 2, W);
        window[k] = gained(raw, params, offsets, y, x);
    }
    barrier(CLK_LOCAL_MEM_FENCE);
    int x = left + lx, y = top + ly;
    int p = (ly + 2) * WINDOW + lx + 2;
    float c = window[p];
    float h = window[p-1] + window[p+1];
    float v = window[p-WINDOW] + window[p+WINDOW];
    float h2 = window[p-2] + window[p+2];
    float v2 = window[p-2*WINDOW] + window[p+2*WINDOW];
    float diagonal = window[p-WINDOW-1] + window[p-WINDOW+1] +
                     window[p+WINDOW-1] + window[p+WINDOW+1];
    float r, g, b;
    if (((x ^ y) & 1) == 0) {
        float horizontal = (5*c + 4*h - h2 - diagonal + .5f*v2) * .125f;
        float vertical = (5*c + 4*v - v2 - diagonal + .5f*h2) * .125f;
        g = c;
        r = (y & 1) == 0 ? vertical : horizontal;
        b = (y & 1) == 0 ? horizontal : vertical;
    } else {
        g = (4*c + 2*(h+v) - h2 - v2) * .125f;
        float opposite = (6*c + 2*diagonal - 1.5f*(h2+v2)) * .125f;
        r = (y & 1) == 0 ? opposite : c;
        b = (y & 1) == 0 ? c : opposite;
    }
    int dest = (y * W + x) * 3;
#if AUDIT_CAPTURE
    int at = display_index(x, y);
    if (at >= 0) {
        trace[at] = c;
        trace[at + 1] = r;
        trace[at + 2] = g;
        trace[at + 3] = b;
    }
#endif
    for (int channel = 0; channel < 3; ++channel) {
        __global const float* m = params + 3 * channel;
        float linear = m[0]*r + m[1]*g + m[2]*b;
        float exposed = clamp(linear * 2.8284271247461903f, 0.0f, 1.0f);
#if AUDIT_CAPTURE
        if (at >= 0) {
            trace[at + 4 + channel] = linear;
            trace[at + 7 + channel] = exposed;
        }
#endif
        rgb[dest + channel] = encode_channel(exposed, lut);
    }
}
)CLC"
