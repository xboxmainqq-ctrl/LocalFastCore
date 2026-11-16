package ru.nexora.runtime;
import java.util.Map;
import java.util.Objects;
/** Server-authoritative placement of basic survival blocks from the hotbar. */
public final class BlockPlacementService {
 private static final double EYE_HEIGHT=1.62d, PLAYER_HALF_WIDTH=0.30d, PLAYER_HEIGHT=1.80d;
 private static final Map<String,BlockState> PLACEABLE=Map.ofEntries(
  Map.entry("minecraft:stone",new BlockState("minecraft:stone")),
  Map.entry("minecraft:cobblestone",new BlockState("minecraft:cobblestone")),
  Map.entry("minecraft:dirt",new BlockState("minecraft:dirt")),
  Map.entry("minecraft:coarse_dirt",new BlockState("minecraft:coarse_dirt")),
  Map.entry("minecraft:podzol",new BlockState("minecraft:podzol")),
  Map.entry("minecraft:grass_block",new BlockState("minecraft:grass_block")),
  Map.entry("minecraft:sand",new BlockState("minecraft:sand")),
  Map.entry("minecraft:oak_log",new BlockState("minecraft:oak_log")),
  Map.entry("minecraft:spruce_log",new BlockState("minecraft:spruce_log")),
  Map.entry("minecraft:birch_log",new BlockState("minecraft:birch_log")),
  Map.entry("minecraft:dark_oak_log",new BlockState("minecraft:dark_oak_log")));
 public enum Result { PLACED,AIR_TARGET,OCCUPIED,OUT_OF_REACH,OUT_OF_WORLD,UNLOADED_CHUNK,INVALID_FACE,UNSUPPORTED_ITEM,INVENTORY_MISMATCH,PLAYER_COLLISION,OCCLUDED }
 public record Outcome(Result result,BlockPosition position,BlockState placedState) {
  public Outcome { Objects.requireNonNull(result); Objects.requireNonNull(position); Objects.requireNonNull(placedState); }
  public boolean placed(){return result==Result.PLACED;}
 }
 private BlockPlacementService(){}
 public static Outcome placeBlock(World world,PlayerInventory inventory,int hotbarSlot,String heldItemId,BlockPosition clickedBlock,int face,double playerX,double playerY,double playerZ){
  Objects.requireNonNull(world); Objects.requireNonNull(inventory); Objects.requireNonNull(heldItemId); Objects.requireNonNull(clickedBlock);
  BlockState state=PLACEABLE.get(heldItemId);
  if(state==null)return out(Result.UNSUPPORTED_ITEM,clickedBlock);
  if(hotbarSlot<0||hotbarSlot>8||hotbarSlot>=inventory.slotCount())return out(Result.INVENTORY_MISMATCH,clickedBlock);
  if(!Double.isFinite(playerX)||!Double.isFinite(playerY)||!Double.isFinite(playerZ))return out(Result.OUT_OF_REACH,clickedBlock);
  if(face<0||face>5)return out(Result.INVALID_FACE,clickedBlock);
  if(!world.height().contains(clickedBlock.y()))return out(Result.OUT_OF_WORLD,clickedBlock);
  if(!world.isChunkLoaded(clickedBlock.chunkPosition()))return out(Result.UNLOADED_CHUNK,clickedBlock);
  if(world.blockAt(clickedBlock).isAir())return out(Result.AIR_TARGET,clickedBlock);
  double dx=clickedBlock.x()+0.5d-playerX,dy=clickedBlock.y()+0.5d-(playerY+EYE_HEIGHT),dz=clickedBlock.z()+0.5d-playerZ;
  if(dx*dx+dy*dy+dz*dz>BlockBreakService.MAX_REACH*BlockBreakService.MAX_REACH)return out(Result.OUT_OF_REACH,clickedBlock);
  if(!hasLineOfSight(world,playerX,playerY+EYE_HEIGHT,playerZ,clickedBlock))return out(Result.OCCLUDED,clickedBlock);
  BlockPosition destination=adjacent(clickedBlock,face);
  if(!world.height().contains(destination.y()))return out(Result.OUT_OF_WORLD,destination);
  if(!world.isChunkLoaded(destination.chunkPosition()))return out(Result.UNLOADED_CHUNK,destination);
  if(!world.blockAt(destination).isAir())return out(Result.OCCUPIED,destination);
  if(intersectsPlayer(destination,playerX,playerY,playerZ))return out(Result.PLAYER_COLLISION,destination);
  synchronized(inventory){
   ItemStack held=inventory.stackAt(hotbarSlot);
   if(held==null||!held.itemId().equals(heldItemId))return out(Result.INVENTORY_MISMATCH,destination);
   world.setBlock(destination,state);
   inventory.removeOne(hotbarSlot);
  }
  return new Outcome(Result.PLACED,destination,state);
 }
 private static BlockPosition adjacent(BlockPosition b,int face){return switch(face){
  case 0->new BlockPosition(b.x(),b.y()-1,b.z()); case 1->new BlockPosition(b.x(),b.y()+1,b.z());
  case 2->new BlockPosition(b.x(),b.y(),b.z()-1); case 3->new BlockPosition(b.x(),b.y(),b.z()+1);
  case 4->new BlockPosition(b.x()-1,b.y(),b.z()); case 5->new BlockPosition(b.x()+1,b.y(),b.z());
  default->throw new IllegalArgumentException("Invalid block face");};}
 private static boolean intersectsPlayer(BlockPosition b,double x,double y,double z){
  return x+PLAYER_HALF_WIDTH>b.x()&&x-PLAYER_HALF_WIDTH<b.x()+1&&y+PLAYER_HEIGHT>b.y()&&y<b.y()+1&&z+PLAYER_HALF_WIDTH>b.z()&&z-PLAYER_HALF_WIDTH<b.z()+1;
 }
 private static boolean hasLineOfSight(World w,double sx,double sy,double sz,BlockPosition target){
  double dx=target.x()+0.5-sx,dy=target.y()+0.5-sy,dz=target.z()+0.5-sz;
  int steps=Math.max(1,(int)Math.ceil(Math.sqrt(dx*dx+dy*dy+dz*dz)*12));
  for(int i=1;i<steps;i++){double f=(double)i/steps;BlockPosition p=new BlockPosition((int)Math.floor(sx+dx*f),(int)Math.floor(sy+dy*f),(int)Math.floor(sz+dz*f));
   if(p.equals(target))continue;
   if(!w.height().contains(p.y())||!w.isChunkLoaded(p.chunkPosition())||!w.blockAt(p).isAir())return false;}
  return true;
 }
 private static Outcome out(Result r,BlockPosition p){return new Outcome(r,p,BlockState.AIR);}
}