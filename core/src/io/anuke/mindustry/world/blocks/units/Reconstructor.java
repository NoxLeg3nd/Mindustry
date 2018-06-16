package io.anuke.mindustry.world.blocks.units;

import com.badlogic.gdx.graphics.g2d.TextureRegion;
import io.anuke.annotations.Annotations.Loc;
import io.anuke.annotations.Annotations.Remote;
import io.anuke.mindustry.Vars;
import io.anuke.mindustry.content.fx.Fx;
import io.anuke.mindustry.entities.Player;
import io.anuke.mindustry.entities.TileEntity;
import io.anuke.mindustry.entities.Units;
import io.anuke.mindustry.gen.CallBlocks;
import io.anuke.mindustry.graphics.Palette;
import io.anuke.mindustry.graphics.Shaders;
import io.anuke.mindustry.net.In;
import io.anuke.mindustry.world.Block;
import io.anuke.mindustry.world.Tile;
import io.anuke.ucore.core.Effects;
import io.anuke.ucore.core.Effects.Effect;
import io.anuke.ucore.core.Graphics;
import io.anuke.ucore.core.Timers;
import io.anuke.ucore.graphics.Draw;
import io.anuke.ucore.graphics.Lines;
import io.anuke.ucore.util.Mathf;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static io.anuke.mindustry.Vars.*;

public class Reconstructor extends Block{
    protected float departTime = 20f;
    protected float arriveTime = 30f;
    protected float powerPerTeleport = 5f;
    protected Effect arriveEffect = Fx.spawn;

    public Reconstructor(String name) {
        super(name);
        update = true;
        solidifes = true;
        hasPower = true;
        configurable = true;
    }

    @Override
    public boolean isSolidFor(Tile tile) {
        ReconstructorEntity entity = tile.entity();

        return entity.solid;
    }

    @Override
    public void drawConfigure(Tile tile) {
        super.drawConfigure(tile);

        ReconstructorEntity entity = tile.entity();

        if(validLink(tile, entity.link)){
            Tile target = world.tile(entity.link);

            Draw.color(Palette.place);
            Lines.square(target.drawx(), target.drawy(),
                    target.block().size * tilesize / 2f + 1f);
            Draw.reset();
        }

        Draw.color(Palette.accent);
        Draw.color();
    }

    @Override
    public boolean onConfigureTileTapped(Tile tile, Tile other){
        if(tile == other) return false;

        ReconstructorEntity entity = tile.entity();

        if(entity.link == other.packedPosition()) {
            CallBlocks.unlinkReconstructor(null, tile, other);
            return false;
        }else if(other.block() instanceof Reconstructor){
            CallBlocks.linkReconstructor(null, tile, other);
            return false;
        }

        return true;
    }

    @Override
    public boolean shouldShowConfigure(Tile tile, Player player) {
        ReconstructorEntity entity = tile.entity();
        return !checkValidTap(tile, entity, player);
    }

    @Override
    public boolean shouldHideConfigure(Tile tile, Player player){
        ReconstructorEntity entity = tile.entity();
        return checkValidTap(tile, entity, player);
    }

    @Override
    public void draw(Tile tile) {
        ReconstructorEntity entity = tile.entity();

        if(entity.solid){
            Draw.rect(name, tile.drawx(), tile.drawy());
        }else{
            Draw.rect(name + "-open", tile.drawx(), tile.drawy());
        }

        if(entity.current != null){
            float progress = entity.departing ? entity.updateTime : (1f - entity.updateTime);

            Player player = entity.current;

            TextureRegion region = Draw.region(player.mech.name);

            Shaders.build.region = region;
            Shaders.build.progress = progress;
            Shaders.build.color.set(Palette.accent);
            Shaders.build.time = -entity.time / 10f;

            Graphics.shader(Shaders.build, false);
            Shaders.build.apply();
            Draw.rect(region, tile.drawx(), tile.drawy());
            Graphics.shader();

            Draw.color(Palette.accent);

            Lines.lineAngleCenter(
                    tile.drawx() + Mathf.sin(entity.time, 6f, Vars.tilesize / 3f * size),
                    tile.drawy(),
                    90,
                    size * Vars.tilesize /2f);

            Draw.reset();
        }
    }

    @Override
    public void update(Tile tile) {
        ReconstructorEntity entity = tile.entity();

        boolean stayOpen = false;

        if(entity.current != null){
            entity.time += Timers.delta();

            entity.solid = true;

            if(entity.departing){
                //force respawn if there's suddenly nothing to link to
                if(!validLink(tile, entity.link)){
                    entity.current.setRespawning(false);
                    return;
                }

                ReconstructorEntity other = world.tile(entity.link).entity();

                entity.updateTime -= Timers.delta()/departTime;
                if(entity.updateTime <= 0f){
                    //TODO veryify power per teleport!
                    other.power.amount -= powerPerTeleport;
                    other.current = entity.current;
                    other.departing = false;
                    other.current.set(other.x, other.y);
                    other.updateTime = 1f;
                    entity.current = null;
                }
            }else{ //else, arriving
                entity.updateTime -= Timers.delta()/arriveTime;

                if(entity.updateTime <= 0f){
                    entity.solid = false;
                    entity.current.setDead(false);

                    Effects.effect(arriveEffect, entity.current);

                    entity.current = null;
                }
            }

        }else{

            if (validLink(tile, entity.link)) {
                Tile other = world.tile(entity.link);
                if (other.entity.power.amount >= powerPerTeleport && Units.anyEntities(tile, 4f, unit -> unit.getTeam() == entity.getTeam() && unit instanceof Player) &&
                        entity.power.amount >= powerPerTeleport) {
                    entity.solid = false;
                    stayOpen = true;
                }
            }

            if (!stayOpen && !entity.solid && !Units.anyEntities(tile)) {
                entity.solid = true;
            }
        }
    }

    @Override
    public void tapped(Tile tile, Player player) {
        ReconstructorEntity entity = tile.entity();

        if(!checkValidTap(tile, entity, player)) return;

        CallBlocks.reconstructPlayer(player, tile);
    }

    @Override
    public TileEntity getEntity() {
        return new ReconstructorEntity();
    }

    protected static boolean checkValidTap(Tile tile, ReconstructorEntity entity, Player player){
        return validLink(tile, entity.link) &&
                Math.abs(player.x - tile.drawx()) <= tile.block().size * tilesize / 2f &&
                Math.abs(player.y - tile.drawy()) <= tile.block().size * tilesize / 2f &&
                entity.current == null && entity.power.amount >= ((Reconstructor)tile.block()).powerPerTeleport;
    }

    protected static boolean validLink(Tile tile, int position){
        Tile other = world.tile(position);
        return other != tile && other != null && other.block() instanceof Reconstructor;
    }

    protected static void unlink(ReconstructorEntity entity){
        Tile other = world.tile(entity.link);

        if(other != null && other.block() instanceof Reconstructor){
            ReconstructorEntity oe = other.entity();
            if(oe.link == entity.tile.packedPosition()){
                oe.link = -1;
            }
        }

        entity.link = -1;
    }

    @Remote(targets = Loc.both, called = Loc.server, in = In.blocks, forward = true)
    public static void reconstructPlayer(Player player, Tile tile){
        ReconstructorEntity entity = tile.entity();

        if(!checkValidTap(tile, entity, player)) return;

        entity.departing = true;
        entity.current = player;
        entity.solid = false;
        entity.set(tile.drawx(), tile.drawy());
        entity.updateTime = 1f;
        player.setDead(true);
        player.setRespawning(true);
        player.setRespawning();
    }

    @Remote(targets = Loc.both, called = Loc.server, in = In.blocks, forward = true)
    public static void linkReconstructor(Player player, Tile tile, Tile other){
        //just in case the client has invalid data
        if(!(tile.entity instanceof ReconstructorEntity) || !(other.entity instanceof ReconstructorEntity)) return;

        ReconstructorEntity entity = tile.entity();
        ReconstructorEntity oe = other.entity();

        //called in main thread to prevent issues
        threads.run(() -> {
            unlink(entity);
            unlink(oe);

            entity.link = other.packedPosition();
            oe.link = tile.packedPosition();
        });
    }

    @Remote(targets = Loc.both, called = Loc.server, in = In.blocks, forward = true)
    public static void unlinkReconstructor(Player player, Tile tile, Tile other){
        //just in case the client has invalid data
        if(!(tile.entity instanceof ReconstructorEntity) || !(other.entity instanceof ReconstructorEntity)) return;

        ReconstructorEntity entity = tile.entity();
        ReconstructorEntity oe = other.entity();

        //called in main thread to prevent issues
        threads.run(() -> {
            unlink(entity);
            unlink(oe);
        });
    }

    public class ReconstructorEntity extends TileEntity{
        public Player current;
        public float updateTime;
        public float time;
        public int link;
        public boolean solid = true, departing;

        @Override
        public void write(DataOutputStream stream) throws IOException {
            stream.writeInt(link);
        }

        @Override
        public void read(DataInputStream stream) throws IOException {
            link = stream.readInt();
        }
    }
}