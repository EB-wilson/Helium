
attribute vec4 a_position;
attribute float a_strength;

uniform mat4 u_projTrans;

varying float v_strength;

void main() {
    v_strength = a_strength;
    gl_Position = u_projTrans * a_position;
}
