import React, { useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import { ColorPalette } from '../types';
import { getRgbNumeric } from '../utils/palette';
import { RotateCw, Maximize2, Grid3X3 } from 'lucide-react';

interface Visualizer3DProps {
  palette: ColorPalette;
  getByteFreqData: () => Uint8Array;
}

export const Visualizer3D: React.FC<Visualizer3DProps> = ({ palette, getByteFreqData }) => {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [isWireframe, setIsWireframe] = useState(false);
  const [autoRotate, setAutoRotate] = useState(true);

  // Mesh resolution
  const ROWS = 48; // Time history slices
  const COLS = 64; // Frequency bins

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const width = container.clientWidth || 800;
    const height = container.clientHeight || 450;

    // Scene, Camera, Renderer
    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x080c14);
    scene.fog = new THREE.FogExp2(0x080c14, 0.015);

    const camera = new THREE.PerspectiveCamera(45, width / height, 0.1, 1000);
    camera.position.set(0, 35, 55);
    camera.lookAt(0, 0, 0);

    const renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' });
    renderer.setSize(width, height);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    container.appendChild(renderer.domElement);

    // Plane Geometry for Waterfall 3D Surface
    const planeGeo = new THREE.PlaneGeometry(60, 50, COLS - 1, ROWS - 1);
    planeGeo.rotateX(-Math.PI / 2);

    const count = planeGeo.attributes.position.count;
    const colors = new Float32Array(count * 3);
    planeGeo.setAttribute('color', new THREE.BufferAttribute(colors, 3));

    const material = new THREE.MeshBasicMaterial({
      vertexColors: true,
      wireframe: isWireframe,
      side: THREE.DoubleSide,
    });

    const mesh = new THREE.Mesh(planeGeo, material);
    scene.add(mesh);

    // Grid Floor
    const gridHelper = new THREE.GridHelper(70, 20, 0x00f0ff, 0x1e293b);
    gridHelper.position.y = -0.5;
    scene.add(gridHelper);

    // History Matrix of Height Values: [ROWS][COLS]
    const history: number[][] = [];
    for (let r = 0; r < ROWS; r++) {
      history.push(new Array(COLS).fill(0));
    }

    // Touch / Mouse Rotation Controls
    let isDragging = false;
    let prevMouseX = 0;
    let prevMouseY = 0;
    let rotX = 0.45;
    let rotY = 0;
    let distance = 60;

    const onMouseDown = (e: MouseEvent) => {
      isDragging = true;
      prevMouseX = e.clientX;
      prevMouseY = e.clientY;
    };

    const onMouseMove = (e: MouseEvent) => {
      if (!isDragging) return;
      const dx = e.clientX - prevMouseX;
      const dy = e.clientY - prevMouseY;
      rotY += dx * 0.01;
      rotX += dy * 0.01;
      rotX = Math.max(0.1, Math.min(Math.PI / 2 - 0.05, rotX));
      prevMouseX = e.clientX;
      prevMouseY = e.clientY;
    };

    const onMouseUp = () => {
      isDragging = false;
    };

    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      distance += e.deltaY * 0.05;
      distance = Math.max(20, Math.min(120, distance));
    };

    // Touch events for mobile
    const onTouchStart = (e: TouchEvent) => {
      if (e.touches.length === 1) {
        isDragging = true;
        prevMouseX = e.touches[0].clientX;
        prevMouseY = e.touches[0].clientY;
      }
    };

    const onTouchMove = (e: TouchEvent) => {
      if (!isDragging || e.touches.length !== 1) return;
      const dx = e.touches[0].clientX - prevMouseX;
      const dy = e.touches[0].clientY - prevMouseY;
      rotY += dx * 0.01;
      rotX += dy * 0.01;
      rotX = Math.max(0.1, Math.min(Math.PI / 2 - 0.05, rotX));
      prevMouseX = e.touches[0].clientX;
      prevMouseY = e.touches[0].clientY;
    };

    const onTouchEnd = () => {
      isDragging = false;
    };

    const dom = renderer.domElement;
    dom.addEventListener('mousedown', onMouseDown);
    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onMouseUp);
    dom.addEventListener('wheel', onWheel, { passive: false });
    dom.addEventListener('touchstart', onTouchStart, { passive: true });
    window.addEventListener('touchmove', onTouchMove, { passive: true });
    window.addEventListener('touchend', onTouchEnd);

    // Animation Loop
    let animId: number;
    let frameCounter = 0;

    const animate = () => {
      animId = requestAnimationFrame(animate);

      frameCounter++;
      // Push new spectrum slice every ~2 frames for smooth waterfall scroll
      if (frameCounter % 2 === 0) {
        const byteData = getByteFreqData();
        const newRow = new Array(COLS).fill(0);
        if (byteData.length > 0) {
          for (let c = 0; c < COLS; c++) {
            // Logarithmic bin mapping
            const bin = Math.min(
              byteData.length - 1,
              Math.floor(Math.pow(c / (COLS - 1), 2) * byteData.length)
            );
            newRow[c] = (byteData[bin] / 255.0) * 16.0; // scale height
          }
        }
        history.pop();
        history.unshift(newRow);
      }

      // Update Mesh Vertices and Colors
      const posAttr = planeGeo.attributes.position;
      const colAttr = planeGeo.attributes.color;

      for (let r = 0; r < ROWS; r++) {
        for (let c = 0; c < COLS; c++) {
          const idx = r * COLS + c;
          const val = history[r][c];
          posAttr.setY(idx, val);

          // Color calculation
          const normVal = Math.min(1.0, val / 14.0);
          const [red, green, blue] = getRgbNumeric(normVal, palette);
          // Dim distant rows slightly
          const distanceFade = 1.0 - (r / ROWS) * 0.55;
          colAttr.setXYZ(idx, red * distanceFade, green * distanceFade, blue * distanceFade);
        }
      }

      posAttr.needsUpdate = true;
      colAttr.needsUpdate = true;

      // Camera rotation
      if (autoRotate && !isDragging) {
        rotY += 0.003;
      }

      camera.position.x = distance * Math.sin(rotY) * Math.cos(rotX);
      camera.position.z = distance * Math.cos(rotY) * Math.cos(rotX);
      camera.position.y = distance * Math.sin(rotX);
      camera.lookAt(0, 4, 0);

      renderer.render(scene, camera);
    };

    animate();

    // Resize Handler
    const handleResize = () => {
      if (!container) return;
      const newW = container.clientWidth;
      const newH = container.clientHeight;
      camera.aspect = newW / newH;
      camera.updateProjectionMatrix();
      renderer.setSize(newW, newH);
    };

    const resizeObserver = new ResizeObserver(handleResize);
    resizeObserver.observe(container);

    return () => {
      cancelAnimationFrame(animId);
      resizeObserver.disconnect();
      dom.removeEventListener('mousedown', onMouseDown);
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', onMouseUp);
      dom.removeEventListener('wheel', onWheel);
      dom.removeEventListener('touchstart', onTouchStart);
      window.removeEventListener('touchmove', onTouchMove);
      window.removeEventListener('touchend', onTouchEnd);
      if (container.contains(renderer.domElement)) {
        container.removeChild(renderer.domElement);
      }
      planeGeo.dispose();
      material.dispose();
      renderer.dispose();
    };
  }, [palette, isWireframe, autoRotate, getByteFreqData]);

  return (
    <div className="relative w-full h-full overflow-hidden select-none">
      <div ref={containerRef} className="w-full h-full cursor-grab active:cursor-grabbing" />

      {/* Floating 3D HUD controls */}
      <div className="absolute top-3 right-3 flex items-center gap-1.5 z-10 bg-slate-900/80 border border-slate-700/60 p-1 rounded-lg backdrop-blur-md">
        <button
          onClick={() => setIsWireframe(!isWireframe)}
          title="Перемкнути сітку (Wireframe)"
          className={`p-1.5 rounded transition ${
            isWireframe ? 'bg-cyan-500/20 text-cyan-400' : 'text-slate-400 hover:text-slate-200'
          }`}
        >
          <Grid3X3 className="w-4 h-4" />
        </button>

        <button
          onClick={() => setAutoRotate(!autoRotate)}
          title="Авто-обертання камери"
          className={`p-1.5 rounded transition ${
            autoRotate ? 'bg-cyan-500/20 text-cyan-400' : 'text-slate-400 hover:text-slate-200'
          }`}
        >
          <RotateCw className="w-4 h-4" />
        </button>
      </div>

      <div className="absolute bottom-3 left-3 text-[11px] font-mono text-slate-400 bg-slate-900/80 px-2.5 py-1 rounded border border-slate-800/80 backdrop-blur-md flex items-center gap-2">
        <span className="w-2 h-2 rounded-full bg-cyan-400 animate-pulse" />
        <span>3D OpenGL Surface | Обертайте мишкою / жестами</span>
      </div>
    </div>
  );
};
