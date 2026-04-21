
uniform sampler2D u_screen;
uniform vec2 u_blurDirection;
uniform vec2 u_screenSize;

varying float v_strength;

const float gaussKernel[7] = float[](0.0477, 0.1196, 0.2078, 0.2498, 0.2078, 0.1196, 0.0477);

void main(){
    vec2 coord = gl_FragCoord.xy/u_screenSize;
    vec2 step = u_blurDirection/u_screenSize*v_strength;

    vec4 mixed = vec4(0.0);
    for (int i = 0; i < 7; i++){
        float off = float(i) - 3.0;
        vec4 color = texture2D(u_screen, coord + u_blurDirection*step*off);
        mixed += color * gaussKernel[i];
    }

    gl_FragColor = mixed;
}
