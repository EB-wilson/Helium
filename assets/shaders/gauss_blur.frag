
uniform sampler2D u_screen;
uniform vec2 u_blurDirection;
uniform vec2 u_screenSize;

varying float v_strength;

void main(){
    vec2 coord = gl_FragCoord.xy/u_screenSize;
    vec2 step = u_blurDirection/u_screenSize*v_strength;

    vec4 mixed =
        texture2D(u_screen, coord - u_blurDirection*step*2.0)*0.01961571 +
        texture2D(u_screen, coord - u_blurDirection*step)*0.20542552 +
        texture2D(u_screen, coord)*0.55991757 +
        texture2D(u_screen, coord + u_blurDirection*step)*0.20542552 +
        texture2D(u_screen, coord + u_blurDirection*step*2.0)*0.01961571;

    gl_FragColor = mixed;
}
