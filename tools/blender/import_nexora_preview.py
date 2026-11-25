"""Import the CI-generated NEXORA OBJ and draw the authoritative Java physics trace.

Run from Blender's Scripting workspace:
    blender --python tools/blender/import_nexora_preview.py -- server/nexora-core/target/blender-preview
"""
import bpy
import csv
import math
import sys
from pathlib import Path
from mathutils import Vector

args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
preview_dir = Path(args[0] if args else "server/nexora-core/target/blender-preview").resolve()
obj_path = preview_dir / "nexora-world.obj"
trace_path = preview_dir / "player-physics.csv"

if not obj_path.is_file():
    raise FileNotFoundError(f"Generated NEXORA OBJ not found: {obj_path}")

# Keep the current scene intact except for objects from an earlier preview run.
for obj in list(bpy.data.objects):
    if obj.name.startswith(("NEXORA_", "PlayerPhysics_")):
        bpy.data.objects.remove(obj, do_unlink=True)

if hasattr(bpy.ops.wm, "obj_import"):
    bpy.ops.wm.obj_import(filepath=str(obj_path), forward_axis="Y", up_axis="Z")
else:
    bpy.ops.import_scene.obj(filepath=str(obj_path))

for obj in bpy.context.selected_objects:
    obj.name = "NEXORA_" + obj.name

if trace_path.is_file():
    with trace_path.open(newline="", encoding="utf-8") as handle:
        samples = list(csv.DictReader(handle))
    if samples:
        curve = bpy.data.curves.new("NEXORA authoritative player path", type="CURVE")
        curve.dimensions = "3D"
        curve.resolution_u = 2
        curve.bevel_depth = 0.055
        curve.bevel_resolution = 3
        spline = curve.splines.new("POLY")
        spline.points.add(len(samples) - 1)
        for point, sample in zip(spline.points, samples):
            point.co = (float(sample["x"]), float(sample["z"]), float(sample["y"]), 1.0)
        path_obj = bpy.data.objects.new("PlayerPhysics_ServerTrajectory", curve)
        bpy.context.collection.objects.link(path_obj)

        first = samples[0]
        marker_mesh = bpy.data.meshes.new("PlayerPhysics_StartMesh")
        marker = bpy.data.objects.new("PlayerPhysics_Start", marker_mesh)
        bpy.context.collection.objects.link(marker)
        marker.location = (float(first["x"]), float(first["z"]), float(first["y"]))
        bpy.ops.object.select_all(action="DESELECT")
        marker.select_set(True)
        bpy.context.view_layer.objects.active = marker
        bpy.ops.object.mode_set(mode="OBJECT")
        bpy.ops.mesh.primitive_uv_sphere_add(segments=16, ring_count=8, radius=0.28,
                                             location=marker.location)
        bpy.context.object.name = "PlayerPhysics_Start"

# Frame the imported geometry in the viewport for immediate inspection.
bpy.ops.object.select_all(action="DESELECT")
terrain = [obj for obj in bpy.context.scene.objects if obj.name.startswith("NEXORA_")]
for obj in terrain:
    obj.select_set(True)
if terrain:
    bpy.context.view_layer.objects.active = terrain[0]
    bpy.ops.view3d.camera_to_view_selected() if bpy.context.area and bpy.context.area.type == "VIEW_3D" else None

print(f"NEXORA Blender preview imported from {preview_dir}")
print("Terrain comes from generated WorldChunk data; player path comes from Java PlayerPhysics.")
