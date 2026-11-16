package ru.nexora.runtime;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class BlockPlacementServiceTest {
 @Test void placesAdjacentBlockAndConsumesOneItem(){
  InMemoryWorld w=loaded();BlockPosition s=new BlockPosition(2,1,2);w.setBlock(s,new BlockState("minecraft:stone"));PlayerInventory inv=new PlayerInventory(UUID.randomUUID());inv.addOne("minecraft:cobblestone");
  var r=BlockPlacementService.placeBlock(w,inv,0,"minecraft:cobblestone",s,5,1.5,1,2.5);
  assertEquals(BlockPlacementService.Result.PLACED,r.result());assertEquals("minecraft:cobblestone",w.blockAt(new BlockPosition(3,1,2)).identifier());assertEquals(0,inv.count("minecraft:cobblestone"));
 }
 @Test void refusesOccupiedDestinationWithoutConsuming(){
  InMemoryWorld w=loaded();BlockPosition s=new BlockPosition(2,1,2),d=new BlockPosition(3,1,2);w.setBlock(s,new BlockState("minecraft:stone"));w.setBlock(d,new BlockState("minecraft:dirt"));PlayerInventory inv=new PlayerInventory(UUID.randomUUID());inv.addOne("minecraft:cobblestone");
  var r=BlockPlacementService.placeBlock(w,inv,0,"minecraft:cobblestone",s,5,1.5,1,2.5);
  assertEquals(BlockPlacementService.Result.OCCUPIED,r.result());assertEquals("minecraft:dirt",w.blockAt(d).identifier());assertEquals(1,inv.count("minecraft:cobblestone"));
 }
 @Test void rejectsMismatchedOrUnsupportedItem(){
  InMemoryWorld w=loaded();BlockPosition s=new BlockPosition(2,1,2);w.setBlock(s,new BlockState("minecraft:stone"));PlayerInventory inv=new PlayerInventory(UUID.randomUUID());inv.addOne("minecraft:dirt");
  assertEquals(BlockPlacementService.Result.INVENTORY_MISMATCH,BlockPlacementService.placeBlock(w,inv,0,"minecraft:cobblestone",s,5,1.5,1,2.5).result());
  assertEquals(BlockPlacementService.Result.UNSUPPORTED_ITEM,BlockPlacementService.placeBlock(w,inv,0,"minecraft:diamond",s,5,1.5,1,2.5).result());assertEquals(1,inv.count("minecraft:dirt"));
 }
 @Test void rejectsInvalidFaceRangeAndSelfCollision(){
  InMemoryWorld w=loaded();BlockPosition s=new BlockPosition(2,1,2);w.setBlock(s,new BlockState("minecraft:stone"));PlayerInventory inv=new PlayerInventory(UUID.randomUUID());inv.addOne("minecraft:dirt");
  assertEquals(BlockPlacementService.Result.INVALID_FACE,BlockPlacementService.placeBlock(w,inv,0,"minecraft:dirt",s,6,1.5,1,2.5).result());
  assertEquals(BlockPlacementService.Result.OUT_OF_REACH,BlockPlacementService.placeBlock(w,inv,0,"minecraft:dirt",s,5,20.5,1,20.5).result());
  assertEquals(BlockPlacementService.Result.PLAYER_COLLISION,BlockPlacementService.placeBlock(w,inv,0,"minecraft:dirt",s,5,2.8,1,2.5).result());assertEquals(1,inv.count("minecraft:dirt"));
 }
 @Test void rejectsAirTargetsAndUnloadedDestination(){
  InMemoryWorld w=loaded();PlayerInventory inv=new PlayerInventory(UUID.randomUUID());inv.addOne("minecraft:dirt");BlockPosition air=new BlockPosition(2,1,2);
  assertEquals(BlockPlacementService.Result.AIR_TARGET,BlockPlacementService.placeBlock(w,inv,0,"minecraft:dirt",air,5,1.5,1,2.5).result());
  BlockPosition edge=new BlockPosition(15,1,2);w.setBlock(edge,new BlockState("minecraft:stone"));
  assertEquals(BlockPlacementService.Result.UNLOADED_CHUNK,BlockPlacementService.placeBlock(w,inv,0,"minecraft:dirt",edge,5,14.5,1,2.5).result());assertEquals(1,inv.count("minecraft:dirt"));
 }
 @Test void supportsAllSixBedrockBlockFaces(){
  int[][] offsets={{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
  for(int face=0;face<6;face++){
   InMemoryWorld w=loaded();BlockPosition clicked=new BlockPosition(8,5,8);
   w.setBlock(clicked,new BlockState("minecraft:stone"));
   PlayerInventory inv=new PlayerInventory(UUID.randomUUID());assertTrue(inv.addOne("minecraft:dirt"));
   var result=BlockPlacementService.placeBlock(w,inv,0,"minecraft:dirt",clicked,face,5.5,2.0,5.5);
   BlockPosition expected=new BlockPosition(8+offsets[face][0],5+offsets[face][1],8+offsets[face][2]);
   assertEquals(BlockPlacementService.Result.PLACED,result.result(),"face "+face);
   assertEquals("minecraft:dirt",w.blockAt(expected).identifier(),"face "+face);
   assertEquals(0,inv.count("minecraft:dirt"),"face "+face);
  }
 }
 private static InMemoryWorld loaded(){InMemoryWorld w=new InMemoryWorld("overworld",new WorldHeight(0,16));w.loadChunk(new GeneratedChunk(new ChunkPosition(0,0),Map.of()));return w;}
}