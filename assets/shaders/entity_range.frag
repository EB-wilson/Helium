#define HIGHP

uniform sampler2D u_texture;

uniform vec2 u_campos;
uniform vec2 u_resolution;
uniform float u_time;
uniform float u_stroke;
uniform float u_alpha;
uniform vec4 u_color;

varying vec2 v_texCoords;

const float threshold = 0.01;
const float PI = 3.14159265359;

void main() {
    vec2 v = u_stroke*(1.0 / u_resolution);

    vec4 base = texture2D(u_texture, v_texCoords);
    vec2 worldCoord = vec2(v_texCoords.x * u_resolution.x + u_campos.x, v_texCoords.y * u_resolution.y + u_campos.y);
    length(1.0);
    float m = min(min(min(
             texture2D(u_texture, v_texCoords + vec2(1.0, 0.0) * v).a,
             texture2D(u_texture, v_texCoords + vec2(0.0, 1.0) * v).a),
             texture2D(u_texture, v_texCoords + vec2(-1.0, 0.0) * v).a),
             texture2D(u_texture, v_texCoords + vec2(0.0, -1.0) * v).a);

    float time = u_time*0.1;
    float a =
        sin((worldCoord.x + worldCoord.y)*0.0831 + time*0.0095) +
        sin((-worldCoord.x + worldCoord.y)*0.075 + time*0.056) +
        sin((worldCoord.x - worldCoord.y)*0.0546 + time*0.21) +
        sin((-worldCoord.x - worldCoord.y)*0.03432 + time*0.712);

    a = (a/4.0 + 1.0)/2.0;

    float s = length(base.rgb);

    float stepA = step(base.a, threshold);
    float stepM = step(m, threshold);
    float stepS = step(s, threshold);

    float con1 = (1.0 - stepA)*stepM;
    float con2 = (1.0 - stepA)*(1.0 - con1);

    vec4 c1 = vec4(base.rgb, u_alpha);
    vec4 c2 = vec4(vec3(1.0), u_alpha);

    gl_FragColor = (vec4(u_color.rgb*stepS + base.rgb*(1.0 - stepS), a))*con1
        + (mix(c1, c2, a)*stepS + c1*(1.0 - stepS))*con2;
}
