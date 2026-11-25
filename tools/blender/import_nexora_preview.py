"""Import and validate generated NEXORA terrain and authoritative physics in Blender.

Run:
    blender --background --python tools/blender/import_nexora_preview.py -- server/nexora-core/target/blender-preview
"""
import bpy
import csv
import json
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
if not trace_path.is_file():
    raise FileNotFoundError("Authoritative Java PlayerPhysics trace is missing")

# Keep the current scene intact except for objects from an earlier preview run.
for obj in list(bpy.data.objects):
    if obj.name.startswith(("NEXORA_", "PlayerPhysics_")):
        bpy.data.objects.remove(obj, do_unlink=True)


def import_obj(path, prefix):
    # Determine imported objects by identity, not by current selection alone.
    before = {obj.as_pointer() for obj in bpy.data.objects}
    bpy.ops.object.select_all(action="DESELECT")
    if hasattr(bpy.ops.wm, "obj_import"):
        bpy.ops.wm.obj_import(filepath=str(path))
    else:
        bpy.ops.import_scene.obj(filepath=str(path))
    imported = [obj for obj in bpy.data.objects if obj.as_pointer() not in before]
    for obj in imported:
        obj.name = prefix + obj.name
    meshes = [obj for obj in imported if obj.type == "MESH"]
    if not meshes:
        raise RuntimeError("Blender imported no mesh geometry from " + str(path))
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

with trace_path.open(newline="", encoding="utf-8") as handle:
    samples = list(csv.DictReader(handle))
if len(samples) < 21:
    raise RuntimeError("PlayerPhysics CSV contains too few trajectory samples")

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

# Validate the actual imported Blender scene, not just the source OBJ file sizes.
terrain_objects = spawn_objects + mountain_objects
terrain_meshes = [obj for obj in terrain_objects if obj.type == "MESH"]
vertex_count = sum(len(obj.data.vertices) for obj in terrain_meshes)
polygon_count = sum(len(obj.data.polygons) for obj in terrain_meshes)
used_materials = sorted({
    slot.material.name
    for obj in terrain_meshes
    for slot in obj.material_slots
    if slot.material is not None
})
if len(terrain_meshes) < 2 or vertex_count < 40_000 or polygon_count < 10_000:
    raise RuntimeError(
        "Imported terrain is incomplete: "
        f"{len(terrain_meshes)} meshes, {vertex_count} vertices, {polygon_count} faces")
if not used_materials:
    raise RuntimeError("Imported terrain has no assigned MTL materials")
if path_obj.name not in bpy.data.objects or len(path_obj.data.splines[0].points) != len(samples):
    raise RuntimeError("PlayerPhysics trajectory was not fully imported")

report = {
    "status": "passed",
    "blender_version": bpy.app.version_string,
    "spawn_mesh_objects": sum(obj.type == "MESH" for obj in spawn_objects),
    "mountain_mesh_objects": sum(obj.type == "MESH" for obj in mountain_objects),
    "terrain_vertices": vertex_count,
    "terrain_faces": polygon_count,
    "materials": used_materials,
    "physics_trace_points": len(samples),
    "physics_start_object": "PlayerPhysics_Start",
    "physics_path_object": path_obj.name,
}
(preview_dir / "blender-validation.json").write_text(
    json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")

# Frame imported geometry in an interactive viewport when one is available.
bpy.ops.object.select_all(action="DESELECT")
for obj in terrain_objects:
    obj.select_set(True)
if terrain_objects:
    bpy.context.view_layer.objects.active = terrain_objects[0]
    if bpy.context.area and bpy.context.area.type == "VIEW_3D":
        bpy.ops.view3d.camera_to_view_selected()

# Render eye-level camera views of the real imported chunk geometry for mobile viewing.
from mathutils import Vector

scene = bpy.context.scene
scene.render.engine = "BLENDER_EEVEE"
scene.render.resolution_x = 1280
scene.render.resolution_y = 720
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = "PNG"
scene.render.film_transparent = False
scene.view_settings.view_transform = "AgX"
scene.world.use_nodes = True
background = scene.world.node_tree.nodes.get("Background")
background.inputs["Color"].default_value = (0.42, 0.55, 0.72, 1.0)
background.inputs["Strength"].default_value = 0.8

sun_data = bpy.data.lights.new("NEXORA_Daylight", type="SUN")
sun_data.energy = 3.0
sun = bpy.data.objects.new("NEXORA_Daylight", sun_data)
bpy.context.collection.objects.link(sun)
sun.rotation_euler = (0.45, -0.35, -0.4)

# Broad fill light keeps block faces readable in headless EEVEE renders.
fill_data = bpy.data.lights.new("NEXORA_Sky_Fill", type="AREA")
fill_data.energy = 14000
fill_data.shape = "DISK"
fill_data.size = 100
fill = bpy.data.objects.new("NEXORA_Sky_Fill", fill_data)
bpy.context.collection.objects.link(fill)
fill.location = (80.0, -60.0, 180.0)
fill.rotation_euler = (Vector((64.0, 64.0, 65.0)) - fill.location).to_track_quat("-Z", "Y").to_euler()

def render_first_person(name, location, target):
    camera_data = bpy.data.cameras.new("NEXORA_" + name + "_Camera")
    camera = bpy.data.objects.new("NEXORA_" + name + "_Camera", camera_data)
    bpy.context.collection.objects.link(camera)
    camera.location = Vector(location)
    camera.rotation_euler = (Vector(target) - camera.location).to_track_quat("-Z", "Y").to_euler()
    camera_data.lens = 52
    camera_data.clip_start = 0.05
    camera_data.clip_end = 1200
    scene.camera = camera
    scene.render.filepath = str(preview_dir / ("nexora-view-" + name + ".png"))
    bpy.ops.render.render(write_still=True)
    bpy.data.objects.remove(camera, do_unlink=True)
    bpy.data.cameras.remove(camera_data)

# Eye-level view from the same spawn point used by the Java physics trace.
render_first_person("spawn", (8.5, 8.5, 66.62), (42.0, 8.5, 65.8))

# Frame the mountain sample from a nearby player-height viewpoint.
mountain_bounds = [
    obj.matrix_world @ Vector(corner)
    for obj in mountain_objects if obj.type == "MESH"
    for corner in obj.bound_box
]
if mountain_bounds:
    highest = max(point.z for point in mountain_bounds)
    # Start outside the mountain mesh at its lower edge and look toward the summit.
    render_first_person("mountains", (160.0, -24.0, 80.0),
                        (160.0, 64.0, max(100.0, highest - 35.0)))

blend_path = preview_dir / "nexora-preview.blend"
bpy.ops.wm.save_as_mainfile(filepath=str(blend_path))
print("NEXORA Blender preview saved to " + str(blend_path))
print("Blender validation: " + json.dumps(report, sort_keys=True))
print("Terrain comes from generated WorldChunk data; player path comes from Java PlayerPhysics.")
