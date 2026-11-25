"""Import the CI-generated NEXORA OBJ and draw the authoritative Java physics trace.

Run from Blender's Scripting workspace:
    blender --python tools/blender/import_nexora_preview.py -- server/nexora-core/target/blender-preview
"""
import bpy
import csv
import sys
from pathlib import Path

args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
preview_dir = Path(args[0] if args else "server/nexora-core/target/blender-preview").resolve()
spawn_obj_path = preview_dir / "nexora-spawn-region.obj"
mountain_obj_path = preview_dir / "nexora-mountain-region.obj"
mountain_center_path = preview_dir / "mountain-center.txt"
trace_path = preview_dir / "player-physics.csv"

if not spawn_obj_path.is_file() or not mountain_obj_path.is_file():
    raise FileNotFoundError("Generated NEXORA spawn or mountain OBJ is missing")

# Keep the current scene intact except for objects from an earlier preview run.
for obj in list(bpy.data.objects):
    if obj.name.startswith(("NEXORA_", "PlayerPhysics_")):
        bpy.data.objects.remove(obj, do_unlink=True)

def import_obj(path, prefix):
    if hasattr(bpy.ops.wm, "obj_import"):
        bpy.ops.wm.obj_import(filepath=str(path))
    else:
        bpy.ops.import_scene.obj(filepath=str(path))
    imported = list(bpy.context.selected_objects)
    for obj in imported:
        obj.name = prefix + obj.name
    return imported

spawn_objects = import_obj(spawn_obj_path, "NEXORA_Spawn_")
mountain_objects = import_obj(mountain_obj_path, "NEXORA_Mountain_")
if mountain_center_path.is_file():
    center_x, center_z = map(int, mountain_center_path.read_text(encoding="utf-8").split(","))
    # Stage the distant mountain sample beside spawn so both regions are inspectable
    # in one viewport; source OBJ files retain their original world coordinates.
    for obj in mountain_objects:
        obj.location.x += 160.0 - center_x
        obj.location.y += 64.0 - center_z

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
        bpy.ops.mesh.primitive_uv_sphere_add(
            segments=16, ring_count=8, radius=0.28,
            location=(float(first["x"]), float(first["z"]), float(first["y"])))
        bpy.context.object.name = "PlayerPhysics_Start"

# Frame the imported geometry in the viewport for immediate inspection.
bpy.ops.object.select_all(action="DESELECT")
terrain = [obj for obj in bpy.context.scene.objects if obj.name.startswith("NEXORA_")]
for obj in terrain:
    obj.select_set(True)
if terrain:
    bpy.context.view_layer.objects.active = terrain[0]
    bpy.ops.view3d.camera_to_view_selected() if bpy.context.area and bpy.context.area.type == "VIEW_3D" else None

blend_path = preview_dir / "nexora-preview.blend"
bpy.ops.wm.save_as_mainfile(filepath=str(blend_path))
print("NEXORA Blender preview saved to " + str(blend_path))
print("Terrain comes from generated WorldChunk data; player path comes from Java PlayerPhysics.")
