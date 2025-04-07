package com.chu.tpa;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.command.CommandManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import com.mojang.brigadier.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.util.math.Vec3d;
public class TpaMod implements ModInitializer {
    public static final String MOD_ID = "tpa-mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
        LOGGER.info("TPA Mod 初始化完成");
        // 注册命令回调事件
	CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			Commands.register(dispatcher);
		});
	}

    /**
     * 命令处理静态内部类，包含所有命令注册和逻辑实现
     */
	public static class Commands {
        // 冷却时间管理系统（UUID -> 最后使用时间戳）
		private static final HashMap<UUID, Long> cooldowns = new HashMap<>();
        // 冷却时长设定为10秒（单位：毫秒）
		private static final long COOLDOWN_TIME = 10000;
        /**
         * 注册所有命令到命令分发器
         * @param dispatcher 命令分发器实例
         */
		public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
            registerTeleportToPlayerCommand(dispatcher);
            registerTeleportToCoordinateCommand(dispatcher);
        }

        /**
         * 注册玩家传送命令 /ta <玩家名>
         */
        private static void registerTeleportToPlayerCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
			dispatcher.register(CommandManager.literal("ta")
					.then(CommandManager.argument("targetPlayer", StringArgumentType.string())
							.suggests((context, builder) -> {
								// 获取当前服务器实例
								MinecraftServer server = context.getSource().getServer();
                        // 获取所有在线玩家的真实用户名（不带格式代码）
								List<String> playerNames = server.getPlayerManager().getPlayerList().stream()
                            .map(player -> player.getGameProfile().getName())  // 使用游戏档案原始名称
										.limit(10)
										.toList();
                        playerNames.forEach(builder::suggest);
								return CompletableFuture.completedFuture(builder.build());
							})
							// 执行传送操作
							.executes(context -> teleportToPlayer(
									context,
									StringArgumentType.getString(context, "targetPlayer")
							))
		));
        }


         // 注册坐标传送命令 /tpa <x> <y> <z>

        private static void registerTeleportToCoordinateCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
			dispatcher.register(CommandManager.literal("tpa")
					.then(CommandManager.argument("x", FloatArgumentType.floatArg())
							.then(CommandManager.argument("y", FloatArgumentType.floatArg())
									.then(CommandManager.argument("z", FloatArgumentType.floatArg())
											// 执行传送操作
											.executes(context -> teleportToCoordinates(
													context,
													FloatArgumentType.getFloat(context, "x"),
													FloatArgumentType.getFloat(context, "y"),
													FloatArgumentType.getFloat(context, "z")
											))
									)
							)
                ));
		}
        /**
         * 执行玩家传送逻辑
         * @return 命令执行结果代码
         */
		private static int teleportToPlayer(CommandContext<ServerCommandSource> context, String targetName) {
			// 获取命令源
			ServerCommandSource source = context.getSource();
            PlayerEntity player = source.getPlayer();

            // 验证执行环境
			if (player == null) {
                source.sendError(Text.of("该命令只能在游戏内由玩家执行"));
				return 0;
			}
            // 冷却时间验证
            UUID uuid = player.getUuid();
            if (!checkCooldown(uuid, source)) {
				return 0;
			}
            // 通过用户缓存查找目标玩家
            source.getServer().getUserCache().findByName(targetName).ifPresentOrElse(
                gameProfile -> source.getServer().execute(() -> {
                    PlayerEntity target = source.getWorld().getPlayerByUuid(gameProfile.getId());
						if (target != null) {
                        executeTeleport(player, target.getPos());
                        source.sendFeedback(() -> Text.of("成功传送至玩家 " + targetName), false);
						} else {
                        source.sendError(Text.of("目标玩家不在线或不存在"));
						}
                }),
                () -> source.sendError(Text.of("无法获取用户数据，请确认玩家名称正确"))
            );
			return Command.SINGLE_SUCCESS;
		}
        /**
         * 执行坐标传送逻辑
         * @return 命令执行结果代码
         */
		private static int teleportToCoordinates(CommandContext<ServerCommandSource> context, float x, float y, float z) {
			// 获取命令源
			ServerCommandSource source = context.getSource();
            PlayerEntity player = source.getPlayer();

            // 验证执行环境
			if (player == null) {
                source.sendError(Text.of("该命令只能在游戏内由玩家执行"));
				return 0;
			}
            // 冷却时间验证
            UUID uuid = player.getUuid();
            if (!checkCooldown(uuid, source)) {
				return 0;
			}
            // 执行传送操作
			source.getServer().execute(() -> {
                executeTeleport(player, new Vec3d(x, y, z));
                source.sendFeedback(() -> Text.of("成功传送至坐标 [" + x + ", " + y + ", " + z + "]"), false);
			});
			// 返回命令成功执行的标志
			return Command.SINGLE_SUCCESS;
		}
        /**
         * 冷却时间检查统一逻辑
         * @return 是否通过冷却检查
         */
        private static boolean checkCooldown(UUID uuid, ServerCommandSource source) {
            if (!canUseCommand(uuid)) {
                long remaining = getRemainingCooldown(uuid);
                source.sendError(Text.of("传送冷却剩余：" + remaining + "秒）"));
                return false;
            }
            setCooldown(uuid);
            return true;
        }

        /**
         * 执行实际传送操作
         */
        private static void executeTeleport(PlayerEntity player, Vec3d targetPos) {
            player.setPos(targetPos.x, targetPos.y, targetPos.z);
        }

        /**
         * 检查指定玩家是否可以使用命令
         * @param uuid 玩家UUID
         * @return 是否已过冷却时间
         */
		private static boolean canUseCommand(UUID uuid) {
            // 如果未记录或已过冷却时间则返回true
			return !cooldowns.containsKey(uuid) ||
					// 检查冷却状态
					(System.currentTimeMillis() - cooldowns.get(uuid)) >= COOLDOWN_TIME;
		}
        /**
         * 设置玩家的最后使用时间戳
         */
		private static void setCooldown(UUID uuid) {
			// 记录当前时间戳
			cooldowns.put(uuid, System.currentTimeMillis());
		}
        /**
         * 计算剩余冷却时间（秒）
         * @return 保证非负的剩余秒数
         */
		private static long getRemainingCooldown(UUID uuid) {
            long elapsed = System.currentTimeMillis() - cooldowns.get(uuid);
            // 使用Math.max确保不会返回负数
            return Math.max(0, (COOLDOWN_TIME - elapsed) / 1000);
		}
	}
}