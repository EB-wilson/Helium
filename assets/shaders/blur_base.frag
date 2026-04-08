
uniform sampler2D u_screen;
uniform vec2 u_screenSize;

varying float v_strength;

void main(){
    vec2 coord = gl_FragCoord.xy/u_screenSize;

    gl_FragColor = texture2D(u_screen, coord);
}
