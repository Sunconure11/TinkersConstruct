package slimeknights.tconstruct.library.utils;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Enchantments;
import net.minecraft.init.MobEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.client.CPacketPlayerDigging;
import net.minecraft.network.play.server.SPacketAnimation;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraft.network.play.server.SPacketEntityVelocity;
import net.minecraft.stats.AchievementList;
import net.minecraft.stats.StatList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.IShearable;
import net.minecraftforge.event.ForgeEventFactory;

import java.util.List;

import slimeknights.tconstruct.TConstruct;
import slimeknights.tconstruct.common.TinkerNetwork;
import slimeknights.tconstruct.library.TinkerRegistry;
import slimeknights.tconstruct.library.events.TinkerToolEvent;
import slimeknights.tconstruct.library.tinkering.Category;
import slimeknights.tconstruct.library.tinkering.TinkersItem;
import slimeknights.tconstruct.library.tools.ToolCore;
import slimeknights.tconstruct.library.tools.ranged.IProjectile;
import slimeknights.tconstruct.library.traits.ITrait;
import slimeknights.tconstruct.tools.TinkerModifiers;
import slimeknights.tconstruct.tools.common.network.ToolBreakAnimationPacket;

public final class ToolHelper {

  private ToolHelper() {
  }

  public static boolean hasCategory(ItemStack stack, Category category) {
    if(stack == null || stack.getItem() == null || !(stack.getItem() instanceof TinkersItem)) {
      return false;
    }

    return ((TinkersItem) stack.getItem()).hasCategory(category);
  }

  /* Basic Tool data */
  public static int getDurabilityStat(ItemStack stack) {
    return getIntTag(stack, Tags.DURABILITY);
  }

  public static int getHarvestLevelStat(ItemStack stack) {
    return getIntTag(stack, Tags.HARVESTLEVEL);
  }

  /** Returns the speed saved on the tool. NOT the actual mining speed, see getActualMiningSpeed */
  public static float getMiningSpeedStat(ItemStack stack) {
    return getfloatTag(stack, Tags.MININGSPEED);
  }

  public static float getAttackStat(ItemStack stack) {
    return getfloatTag(stack, Tags.ATTACK);
  }

  public static float getActualAttack(ItemStack stack) {
    float damage = getAttackStat(stack);
    if(stack != null && stack.getItem() instanceof ToolCore) {
      damage *= ((ToolCore) stack.getItem()).damagePotential();
    }
    return damage;
  }

  /**
   * Returns the attack speed saved on the tool.
   * This is normally just a number from 1 to 2, the actual attack speed is in getActualAttackSpeed
   */
  public static float getAttackSpeedStat(ItemStack stack) {
    return getfloatTag(stack, Tags.ATTACKSPEEDMULTIPLIER);
  }

  /** Returns the actual attack speed */
  public static float getActualAttackSpeed(ItemStack stack) {
    float speed = getAttackSpeedStat(stack);
    if(stack != null && stack.getItem() instanceof ToolCore) {
      speed *= ((ToolCore) stack.getItem()).attackSpeed();
    }
    return speed;
  }

  /** Returns the actual mining speed. */
  public static float getActualMiningSpeed(ItemStack stack) {
    float speed = getMiningSpeedStat(stack);
    if(stack != null && stack.getItem() instanceof ToolCore) {
      speed *= ((ToolCore) stack.getItem()).miningSpeedModifier();
    }
    return speed;
  }

  public static int getFreeModifiers(ItemStack stack) {
    return getIntTag(stack, Tags.FREE_MODIFIERS);
  }

  public static List<ITrait> getTraits(ItemStack stack) {
    List<ITrait> traits = Lists.newLinkedList();
    NBTTagList traitsTagList = TagUtil.getTraitsTagList(stack);
    for(int i = 0; i < traitsTagList.tagCount(); i++) {
      ITrait trait = TinkerRegistry.getTrait(traitsTagList.getStringTagAt(i));
      if(trait != null) {
        traits.add(trait);
      }
    }
    return traits;
  }

  public static float calcDigSpeed(ItemStack stack, IBlockState blockState) {
    if(blockState == null) {
      return 0f;
    }

    if(!stack.hasTagCompound()) {
      return 1f;
    }

    // check if the tool has the correct class and harvest level
    if(!canHarvest(stack, blockState)) {
      return 0f;
    }

    if(isBroken(stack)) {
      return 0.3f;
    }

    // calculate speed depending on stats
    NBTTagCompound tag = TagUtil.getToolTag(stack);
    float speed = tag.getFloat(Tags.MININGSPEED);

    if(stack.getItem() instanceof ToolCore) {
      speed *= ((ToolCore) stack.getItem()).miningSpeedModifier();
    }

    return speed;
  }

  /**
   * Returns true if the tool is effective for harvesting the given block.
   */
  public static boolean isToolEffective(ItemStack stack, IBlockState state) {
    // check material
    for(String type : stack.getItem().getToolClasses(stack)) {
      if(state.getBlock().isToolEffective(type, state)) {
        return true;
      }
    }

    return false;
  }

  // also checks for the tools effectiveness
  public static boolean isToolEffective2(ItemStack stack, IBlockState state) {
    if(isToolEffective(stack, state)) {
      return true;
    }

    // this will be the only place besides fortify where a modifier is hardcoded. I promise. :L
    if(TinkerUtil.hasModifier(TagUtil.getTagSafe(stack), TinkerModifiers.modBlasting.getIdentifier()) && state.getMaterial().isToolNotRequired()) {
      return true;
    }

    return stack.getItem() instanceof ToolCore && ((ToolCore) stack.getItem()).isEffective(state);

  }

  /**
   * Checks if an item has the right harvest level of the correct type for the block.
   */
  public static boolean canHarvest(ItemStack stack, IBlockState state) {
    Block block = state.getBlock();

    // doesn't require a tool
    if(block.getMaterial(state).isToolNotRequired()) {
      return true;
    }

    String type = block.getHarvestTool(state);
    int level = block.getHarvestLevel(state);

    return stack.getItem().getHarvestLevel(stack, type) >= level;
  }

  /* Harvesting */


  public static ImmutableList<BlockPos> calcAOEBlocks(ItemStack stack, World world, EntityPlayer player, BlockPos origin, int width, int height, int depth) {
    return calcAOEBlocks(stack, world, player, origin, width, height, depth, -1);
  }

  public static ImmutableList<BlockPos> calcAOEBlocks(ItemStack stack, World world, EntityPlayer player, BlockPos origin, int width, int height, int depth, int distance) {
    // only works with toolcore because we need the raytrace call
    if(stack == null || !(stack.getItem() instanceof ToolCore)) {
      return ImmutableList.of();
    }

    // find out where the player is hitting the block
    IBlockState state = world.getBlockState(origin);
    Block block = state.getBlock();

    if(!isToolEffective2(stack, state)) {
      return ImmutableList.of();
    }

    if(block.getMaterial(state) == Material.AIR) {
      // what are you DOING?
      return ImmutableList.of();
    }

    RayTraceResult mop = ((ToolCore) stack.getItem()).rayTrace(world, player, false);
    if(mop == null) {
      return ImmutableList.of();
    }

    // fire event
    TinkerToolEvent.ExtraBlockBreak event = TinkerToolEvent.ExtraBlockBreak.fireEvent(stack, player, state, width, height, depth, distance);
    if(event.isCanceled()) {
      return ImmutableList.of();
    }
    width = event.width;
    height = event.height;
    depth = event.depth;
    distance = event.distance;

    // we know the block and we know which side of the block we're hitting. time to calculate the depth along the different axes
    int x, y, z;
    BlockPos start = origin;
    switch(mop.sideHit) {
      case DOWN:
      case UP:
        // x y depends on the angle we look?
        Vec3i vec = player.getHorizontalFacing().getDirectionVec();
        x = vec.getX() * height + vec.getZ() * width;
        y = mop.sideHit.getAxisDirection().getOffset() * -depth;
        z = vec.getX() * width + vec.getZ() * height;
        start = start.add(-x / 2, 0, -z / 2);
        if(x % 2 == 0) {
          if(x > 0 && mop.hitVec.xCoord - mop.getBlockPos().getX() > 0.5d) {
            start = start.add(1, 0, 0);
          }
          else if(x < 0 && mop.hitVec.xCoord - mop.getBlockPos().getX() < 0.5d) {
            start = start.add(-1, 0, 0);
          }
        }
        if(z % 2 == 0) {
          if(z > 0 && mop.hitVec.zCoord - mop.getBlockPos().getZ() > 0.5d) {
            start = start.add(0, 0, 1);
          }
          else if(z < 0 && mop.hitVec.zCoord - mop.getBlockPos().getZ() < 0.5d) {
            start = start.add(0, 0, -1);
          }
        }
        break;
      case NORTH:
      case SOUTH:
        x = width;
        y = height;
        z = mop.sideHit.getAxisDirection().getOffset() * -depth;
        start = start.add(-x / 2, -y / 2, 0);
        if(x % 2 == 0 && mop.hitVec.xCoord - mop.getBlockPos().getX() > 0.5d) {
          start = start.add(1, 0, 0);
        }
        if(y % 2 == 0 && mop.hitVec.yCoord - mop.getBlockPos().getY() > 0.5d) {
          start = start.add(0, 1, 0);
        }
        break;
      case WEST:
      case EAST:
        x = mop.sideHit.getAxisDirection().getOffset() * -depth;
        y = height;
        z = width;
        start = start.add(-0, -y / 2, -z / 2);
        if(y % 2 == 0 && mop.hitVec.yCoord - mop.getBlockPos().getY() > 0.5d) {
          start = start.add(0, 1, 0);
        }
        if(z % 2 == 0 && mop.hitVec.zCoord - mop.getBlockPos().getZ() > 0.5d) {
          start = start.add(0, 0, 1);
        }
        break;
      default:
        x = y = z = 0;
    }

    ImmutableList.Builder<BlockPos> builder = ImmutableList.builder();
    for(int xp = start.getX(); xp != start.getX() + x; xp += x / MathHelper.abs_int(x)) {
      for(int yp = start.getY(); yp != start.getY() + y; yp += y / MathHelper.abs_int(y)) {
        for(int zp = start.getZ(); zp != start.getZ() + z; zp += z / MathHelper.abs_int(z)) {
          // don't add the origin block
          if(xp == origin.getX() && yp == origin.getY() && zp == origin.getZ()) {
            continue;
          }
          if(distance > 0 && MathHelper.abs_int(xp - origin.getX()) + MathHelper.abs_int(yp - origin.getY()) + MathHelper.abs_int(
              zp - origin.getZ()) > distance) {
            continue;
          }
          BlockPos pos = new BlockPos(xp, yp, zp);
          if(isToolEffective2(stack, world.getBlockState(pos))) {
            builder.add(pos);
          }
        }
      }
    }

    return builder.build();
  }

  public static void breakExtraBlock(ItemStack stack, World world, EntityPlayer player, BlockPos pos, BlockPos refPos) {
    // prevent calling that stuff for air blocks, could lead to unexpected behaviour since it fires events
    if(world.isAirBlock(pos)) {
      return;
    }

    //if(!(player instanceof EntityPlayerMP)) {
    //return;
    //}

    // check if the block can be broken, since extra block breaks shouldn't instantly break stuff like obsidian
    // or precious ores you can't harvest while mining stone
    IBlockState state = world.getBlockState(pos);
    Block block = state.getBlock();

    // only effective materials
    if(!isToolEffective2(stack, state)) {
      return;
    }

    IBlockState refState = world.getBlockState(refPos);
    float refStrength = ForgeHooks.blockStrength(refState, player, world, refPos);
    float strength = ForgeHooks.blockStrength(state, player, world, pos);

    // only harvestable blocks that aren't impossibly slow to harvest
    if(!ForgeHooks.canHarvestBlock(block, player, world, pos) || refStrength / strength > 10f) {
      return;
    }

    // From this point on it's clear that the player CAN break the block

    if(player.capabilities.isCreativeMode) {
      block.onBlockHarvested(world, pos, state, player);
      if(block.removedByPlayer(state, world, pos, player, false)) {
        block.onBlockDestroyedByPlayer(world, pos, state);
      }

      // send update to client
      if(!world.isRemote) {
        ((EntityPlayerMP) player).connection.sendPacket(new SPacketBlockChange(world, pos));
      }
      return;
    }

    // callback to the tool the player uses. Called on both sides. This damages the tool n stuff.
    stack.onBlockDestroyed(world, state, pos, player);

    // server sided handling
    if(!world.isRemote) {
      // send the blockbreak event
      int xp = ForgeHooks.onBlockBreakEvent(world, ((EntityPlayerMP) player).interactionManager.getGameType(), (EntityPlayerMP) player, pos);
      if(xp == -1) {
        return;
      }


      // serverside we reproduce ItemInWorldManager.tryHarvestBlock

      TileEntity tileEntity = world.getTileEntity(pos);
      // ItemInWorldManager.removeBlock
      if(block.removedByPlayer(state, world, pos, player, true)) // boolean is if block can be harvested, checked above
      {
        block.onBlockDestroyedByPlayer(world, pos, state);
        block.harvestBlock(world, player, pos, state, tileEntity, stack);
        block.dropXpOnBlockBreak(world, pos, xp);
      }

      // always send block update to client
      EntityPlayerMP mpPlayer = (EntityPlayerMP) player;
      mpPlayer.connection.sendPacket(new SPacketBlockChange(world, pos));
    }
    // client sided handling
    else {
      PlayerControllerMP pcmp = Minecraft.getMinecraft().playerController;
      // clientside we do a "this clock has been clicked on long enough to be broken" call. This should not send any new packets
      // the code above, executed on the server, sends a block-updates that give us the correct state of the block we destroy.

      // following code can be found in PlayerControllerMP.onPlayerDestroyBlock
      world.playBroadcastSound(2001, pos, Block.getStateId(state));
      if(block.removedByPlayer(state, world, pos, player, true)) {
        block.onBlockDestroyedByPlayer(world, pos, state);
      }
      // callback to the tool
      stack.onBlockDestroyed(world, state, pos, player);

      if(stack.stackSize == 0 && stack == player.getHeldItemMainhand()) {
        ForgeEventFactory.onPlayerDestroyItem(player, stack, EnumHand.MAIN_HAND);
        player.setHeldItem(EnumHand.MAIN_HAND, null);
      }

      // send an update to the server, so we get an update back
      //if(PHConstruct.extraBlockUpdates)
      Minecraft.getMinecraft().getConnection().sendPacket(new CPacketPlayerDigging(CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK, pos, Minecraft
          .getMinecraft().objectMouseOver.sideHit));
    }
  }

  public static boolean shearBlock(ItemStack itemstack, World world, EntityPlayer player, BlockPos pos) {
    // only serverside since it creates entities
    if(world.isRemote) {
      return false;
    }

    Block block = world.getBlockState(pos).getBlock();
    if(block instanceof IShearable) {
      IShearable target = (IShearable) block;
      if(target.isShearable(itemstack, world, pos)) {
        int fortune = EnchantmentHelper.getEnchantmentLevel(Enchantments.FORTUNE, itemstack);
        List<ItemStack> drops = target.onSheared(itemstack, world, pos, fortune);

        for(ItemStack stack : drops) {
          float f = 0.7F;
          double d = (double) (TConstruct.random.nextFloat() * f) + (double) (1.0F - f) * 0.5D;
          double d1 = (double) (TConstruct.random.nextFloat() * f) + (double) (1.0F - f) * 0.5D;
          double d2 = (double) (TConstruct.random.nextFloat() * f) + (double) (1.0F - f) * 0.5D;
          EntityItem entityitem = new EntityItem(player.worldObj, (double) pos.getX() + d, (double) pos.getY() + d1, (double) pos.getZ() + d2, stack);
          entityitem.setDefaultPickupDelay();
          world.spawnEntityInWorld(entityitem);
        }

        itemstack.damageItem(1, player);
        //player.addStat(net.minecraft.stats.StatList.mineBlockStatArray[Block.getIdFromBlock(block)], 1);

        world.setBlockToAir(pos);

        return true;
      }
    }
    return false;
  }


  /* Tool Durability */

  public static int getCurrentDurability(ItemStack stack) {
    return stack.getMaxDamage() - stack.getItemDamage();
  }

  public static int getMaxDurability(ItemStack stack) {
    return stack.getMaxDamage();
  }

  /** Damages the tool. Entity is only needed in case the tool breaks for rendering the break effect. */
  public static void damageTool(ItemStack stack, int amount, EntityLivingBase entity) {
    if(amount == 0 || isBroken(stack)) {
      return;
    }

    int actualAmount = amount;
    NBTTagList list = TagUtil.getTraitsTagList(stack);
    for(int i = 0; i < list.tagCount(); i++) {
      ITrait trait = TinkerRegistry.getTrait(list.getStringTagAt(i));
      if(trait != null) {
        if(amount > 0) {
          actualAmount = trait.onToolDamage(stack, amount, actualAmount, entity);
        }
        else {
          actualAmount = trait.onToolHeal(stack, amount, actualAmount, entity);
        }
      }
    }

    // ensure we never deal more damage than durability
    actualAmount = Math.min(actualAmount, getCurrentDurability(stack));
    stack.setItemDamage(stack.getItemDamage() + actualAmount);

    if(getCurrentDurability(stack) == 0) {
      breakTool(stack, entity);
    }
  }

  public static void healTool(ItemStack stack, int amount, EntityLivingBase entity) {
    damageTool(stack, -amount, entity);
  }

  public static boolean isBroken(ItemStack stack) {
    return TagUtil.getToolTag(stack).getBoolean(Tags.BROKEN);
  }

  public static void breakTool(ItemStack stack, EntityLivingBase entity) {
    NBTTagCompound tag = TagUtil.getToolTag(stack);
    tag.setBoolean(Tags.BROKEN, true);
    TagUtil.setToolTag(stack, tag);

    if(entity instanceof EntityPlayerMP) {
      TinkerNetwork.sendTo(new ToolBreakAnimationPacket(stack), (EntityPlayerMP) entity);
    }
  }

  public static void unbreakTool(ItemStack stack) {
    if(isBroken(stack)) {
      // ensure correct damage value
      stack.setItemDamage(stack.getMaxDamage());

      // setItemDamage might break the tool again, so we do this afterwards
      NBTTagCompound tag = TagUtil.getToolTag(stack);
      tag.setBoolean(Tags.BROKEN, false);
      TagUtil.setToolTag(stack, tag);
    }
  }

  public static void repairTool(ItemStack stack, int amount) {
    // entity is optional, only needed for rendering break effect, never needed when repairing
    repairTool(stack, amount, null);
  }

  public static void repairTool(ItemStack stack, int amount, EntityLivingBase entity) {
    unbreakTool(stack);

    TinkerToolEvent.OnRepair.fireEvent(stack, amount);

    healTool(stack, amount, entity);
  }


  /* Dealing tons of damage */

  public static boolean attackEntity(ItemStack stack, ToolCore tool, EntityLivingBase attacker, Entity targetEntity) {
    return attackEntity(stack, tool, attacker, targetEntity, null);
  }

  /**
   * Makes all the calls to attack an entity. Takes enchantments and potions and traits into account. Basically call this when a tool deals damage.
   * Most of this function is the same as {@link EntityPlayer#attackTargetEntityWithCurrentItem(Entity targetEntity)}
   */
  public static boolean attackEntity(ItemStack stack, ToolCore tool, EntityLivingBase attacker, Entity targetEntity, Entity projectileEntity) {
    // nothing to do, no target?
    if(targetEntity == null || !targetEntity.canBeAttackedWithItem() || targetEntity.hitByEntity(attacker) || !stack.hasTagCompound()) {
      return false;
    }
    if(!(targetEntity instanceof EntityLivingBase)) {
      return false;
    }
    if(isBroken(stack)) {
      return false;
    }
    if(attacker == null) {
      return false;
    }
    boolean isProjectile = projectileEntity != null;
    EntityLivingBase target = (EntityLivingBase) targetEntity;

    EntityPlayer player = null;
    if(attacker instanceof EntityPlayer) {
      player = (EntityPlayer) attacker;
    }

    // traits on the tool
    List<ITrait> traits = Lists.newLinkedList();
    NBTTagList traitsTagList = TagUtil.getTraitsTagList(stack);
    for(int i = 0; i < traitsTagList.tagCount(); i++) {
      ITrait trait = TinkerRegistry.getTrait(traitsTagList.getStringTagAt(i));
      if(trait != null) {
        traits.add(trait);
      }
    }

    // players base damage (includes tools damage stat)
    float baseDamage = (float) attacker.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue();

    // missing because not supported by tcon tools: vanilla damage enchantments, we have our own modifiers
    // missing because not supported by tcon tools: vanilla knockback enchantments, we have our own modifiers
    float baseKnockback = attacker.isSprinting() ? 1 : 0;

    // calculate if it's a critical hit
    boolean isCritical = attacker.fallDistance > 0.0F && !attacker.onGround && !attacker.isOnLadder() && !attacker.isInWater() && !attacker.isPotionActive(MobEffects.BLINDNESS) && !attacker.isRiding();
    for(ITrait trait : traits) {
      if(trait.isCriticalHit(stack, attacker, target)) {
        isCritical = true;
      }
    }

    // calculate actual damage
    float damage = baseDamage;
    for(ITrait trait : traits) {
      damage = trait.damage(stack, attacker, target, baseDamage, damage, isCritical);
    }

    // apply critical damage
    if(isCritical) {
      damage *= 1.5f;
    }

    // calculate cutoff
    damage = calcCutoffDamage(damage, tool.damageCutoff());

    // calculate actual knockback
    float knockback = baseKnockback;
    for(ITrait trait : traits) {
      knockback = trait.knockBack(stack, attacker, target, damage, baseKnockback, knockback, isCritical);
    }

    // missing because not supported by tcon tools: vanilla fire aspect enchantments, we have our own modifiers

    float oldHP = target.getHealth();

    double oldVelX = target.motionX;
    double oldVelY = target.motionY;
    double oldVelZ = target.motionZ;

    // apply cooldown damage decrease
    if(player != null) {
      float f2 = player.getCooledAttackStrength(0.5F);
      damage *= (0.2F + f2 * f2 * 0.8F);
    }

    int hurtResistantTime = target.hurtResistantTime;
    // deal the damage
    for(ITrait trait : traits) {
      trait.onHit(stack, attacker, target, damage, isCritical);
      // reset hurt reristant time
      target.hurtResistantTime = hurtResistantTime;
    }

    boolean hit = false;
    if(isProjectile && tool instanceof IProjectile) {
      hit = ((IProjectile) tool).dealDamageRanged(stack, projectileEntity, attacker, target, damage);
    }
    else {
      hit = tool.dealDamage(stack, attacker, target, damage);
    }


    // did we hit?
    if(hit) {
      // actual damage dealt
      float damageDealt = oldHP - target.getHealth();

      // apply knockback modifier
      oldVelX = target.motionX = oldVelX + (target.motionX - oldVelX) * tool.knockback();
      oldVelY = target.motionY = oldVelY + (target.motionY - oldVelY) * tool.knockback() / 3f;
      oldVelZ = target.motionZ = oldVelZ + (target.motionZ - oldVelZ) * tool.knockback();

      // apply knockback
      if(knockback > 0f) {
        double velX = -MathHelper.sin(attacker.rotationYaw * (float) Math.PI / 180.0F) * knockback * 0.5F;
        double velZ = MathHelper.cos(attacker.rotationYaw * (float) Math.PI / 180.0F) * knockback * 0.5F;
        targetEntity.addVelocity(velX, 0.1d, velZ);

        // slow down player
        attacker.motionX *= 0.6f;
        attacker.motionZ *= 0.6f;
        attacker.setSprinting(false);
      }

      // Send movement changes caused by attacking directly to hit players.
      // I guess this is to allow better handling at the hit players side? No idea why it resets the motion though.
      if(targetEntity instanceof EntityPlayerMP && targetEntity.velocityChanged) {
        ((EntityPlayerMP) targetEntity).connection.sendPacket(new SPacketEntityVelocity(targetEntity));
        targetEntity.velocityChanged = false;
        targetEntity.motionX = oldVelX;
        targetEntity.motionY = oldVelY;
        targetEntity.motionZ = oldVelZ;
      }

      if(player != null) {
        // vanilla critical callback
        if(isCritical) {
          player.onCriticalHit(target);
        }

        // "magical" critical damage? (aka caused by modifiers)
        if(damage > baseDamage) {
          // this usually only displays some particles :)
          player.onEnchantmentCritical(targetEntity);
        }

        // vanilla achievement support :D
        if(damage >= 18f) {
          player.addStat(AchievementList.OVERKILL);
        }
      }

      attacker.setLastAttacker(target);
      // Damage indicator particles


      // we don't support vanilla thorns or antispider enchantments
      //EnchantmentHelper.applyThornEnchantments(target, player);
      //EnchantmentHelper.applyArthropodEnchantments(player, target);


      // call post-hit callbacks before reducing the durability
      for(ITrait trait : traits) {
        trait.afterHit(stack, attacker, target, damageDealt, isCritical, hit); // hit is always true
      }

      // damage the tool
      if(player != null) {
        stack.hitEntity(target, player);
        if(!player.capabilities.isCreativeMode && !isProjectile) {
          tool.reduceDurabilityOnHit(stack, player, damage);
        }

        player.addStat(StatList.DAMAGE_DEALT, Math.round(damageDealt * 10f));
        player.addExhaustion(0.3f);

        if(player.worldObj instanceof WorldServer && damageDealt > 2f) {
          int k = (int) (damageDealt * 0.5);
          ((WorldServer) player.worldObj).spawnParticle(EnumParticleTypes.DAMAGE_INDICATOR, targetEntity.posX, targetEntity.posY + (double) (targetEntity.height * 0.5F), targetEntity.posZ, k, 0.1D, 0.0D, 0.1D, 0.2D);
        }

        // cooldown for non-projectiles
        if(!isProjectile) {
          player.resetCooldown();
        }
      }
      else if(!isProjectile) {
        tool.reduceDurabilityOnHit(stack, null, damage);
      }
    }

    return true;
  }

  public static float calcCutoffDamage(float damage, float cutoff) {
    float p = 1f;
    float d = damage;
    damage = 0f;
    while(d > cutoff) {
      damage += p * cutoff;
      // safety for ridiculous values
      if(p > 0.001f) {
        p *= 0.9f;
      }
      else {
        damage += p * cutoff * ((d / cutoff) - 1f);
        return damage;
      }
      d -= cutoff;
    }

    damage += p * d;

    return damage;
  }

  public static float getActualDamage(ItemStack stack, EntityLivingBase player) {
    float damage = (float) player.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue();
    damage += ToolHelper.getActualAttack(stack);

    if(stack.getItem() instanceof ToolCore) {
      damage = ToolHelper.calcCutoffDamage(damage, ((ToolCore) stack.getItem()).damageCutoff());
    }

    return damage;
  }

  public static void swingItem(int speed, EntityLivingBase entity) {
    if(!entity.isSwingInProgress || entity.swingProgressInt >= 3 || entity.swingProgressInt < 0) {
      entity.swingProgressInt = Math.min(4, -1 + speed);
      entity.isSwingInProgress = true;

      if(entity.worldObj instanceof WorldServer) {
        ((WorldServer) entity.worldObj).getEntityTracker().sendToAllTrackingEntity(entity, new SPacketAnimation(entity, 0));
      }
    }
  }

  /* Helper Functions */

  static int getIntTag(ItemStack stack, String key) {
    NBTTagCompound tag = TagUtil.getToolTag(stack);

    return tag.getInteger(key);
  }

  static float getfloatTag(ItemStack stack, String key) {
    NBTTagCompound tag = TagUtil.getToolTag(stack);

    return tag.getFloat(key);
  }
}
