/*
 * Copyright 2015 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.rendering.nui.layers.mainMenu;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.terasology.config.Config;
import org.terasology.config.ServerInfo;
import org.terasology.engine.GameEngine;
import org.terasology.engine.modes.StateLoading;
import org.terasology.engine.module.ModuleManager;
import org.terasology.module.ModuleRegistry;
import org.terasology.naming.Name;
import org.terasology.naming.NameVersion;
import org.terasology.naming.Version;
import org.terasology.network.JoinStatus;
import org.terasology.network.NetworkSystem;
import org.terasology.network.ServerInfoMessage;
import org.terasology.registry.In;
import org.terasology.rendering.FontColor;
import org.terasology.rendering.nui.Color;
import org.terasology.rendering.nui.CoreScreenLayer;
import org.terasology.rendering.nui.UIWidget;
import org.terasology.rendering.nui.WidgetUtil;
import org.terasology.rendering.nui.databinding.BindHelper;
import org.terasology.rendering.nui.databinding.IntToStringBinding;
import org.terasology.rendering.nui.databinding.ListSelectionBinding;
import org.terasology.rendering.nui.databinding.ReadOnlyBinding;
import org.terasology.rendering.nui.itemRendering.StringTextRenderer;
import org.terasology.rendering.nui.widgets.ActivateEventListener;
import org.terasology.rendering.nui.widgets.ItemActivateEventListener;
import org.terasology.rendering.nui.widgets.UIButton;
import org.terasology.rendering.nui.widgets.UILabel;
import org.terasology.rendering.nui.widgets.UIList;
import org.terasology.world.internal.WorldInfo;

import com.google.common.base.Function;
import com.google.common.base.Joiner;

/**
 * @author Immortius
 */
public class JoinGameScreen extends CoreScreenLayer {

    @In
    private Config config;

    @In
    private NetworkSystem networkSystem;

    @In
    private GameEngine engine;

    @In
    private ModuleManager moduleManager;

    private Map<ServerInfo, Future<ServerInfoMessage>> extInfo = new HashMap<>();

    @Override
    public void initialise() {
        UIList<ServerInfo> serverList = find("serverList", UIList.class);
        if (serverList != null) {
            configureScreen(serverList);
        }

        WidgetUtil.trySubscribe(this, "close", new ActivateEventListener() {
            @Override
            public void onActivated(UIWidget button) {
                config.save();
                getManager().popScreen();
            }
        });
    }

    @Override
    public boolean isLowerLayerVisible() {
        return false;
    }

    private void join(final String address, final int port) {
        Callable<JoinStatus> operation = new Callable<JoinStatus>() {

            @Override
            public JoinStatus call() throws InterruptedException {
                JoinStatus joinStatus = networkSystem.join(address, port);
                return joinStatus;
            }
        };

        final WaitPopup<JoinStatus> popup = getManager().pushScreen(WaitPopup.ASSET_URI, WaitPopup.class);
        popup.setMessage("Join Game", "Connecting to '" + address + ":" + port + "' - please wait ...");
        popup.onSuccess(new Function<JoinStatus, Void>() {

            @Override
            public Void apply(JoinStatus result) {
                if (result.getStatus() != JoinStatus.Status.FAILED) {
                    engine.changeState(new StateLoading(result));
                } else {
                    MessagePopup screen = getManager().pushScreen(MessagePopup.ASSET_URI, MessagePopup.class);
                    screen.setMessage("Failed to Join", "Could not connect to server - " + result.getErrorMessage());
                }
                return null;
            }
        });
        popup.startOperation(operation, true);

    }

    private void configureScreen(final UIList<ServerInfo> serverList) {
        final List<ServerInfo> locals = config.getNetwork().getServers();
        final ServerListDownloader downloader = new ServerListDownloader(config.getNetwork().getMasterServer());

        serverList.bindList(new CombinedListBinding<ServerInfo>(locals, downloader.getServers()));
        serverList.setItemRenderer(new StringTextRenderer<ServerInfo>() {
            @Override
            public String getString(ServerInfo value) {
                String name = value.getName();

                if (locals.contains(value)) {
                    name = "(custom) " + name;
                }

                return name;
            }
        });
        serverList.subscribe(new ItemActivateEventListener<ServerInfo>() {
            @Override
            public void onItemActivated(UIWidget widget, ServerInfo item) {
                join(item.getAddress(), item.getPort());
            }
        });

        final ListSelectionBinding<ServerInfo> infoBinding = new ListSelectionBinding<ServerInfo>(serverList);

        UILabel name = find("name", UILabel.class);
        if (name != null) {
            name.bindText(BindHelper.bindBoundBeanProperty("name", infoBinding, ServerInfo.class, String.class));
        }

        UILabel address = find("address", UILabel.class);
        if (address != null) {
            address.bindText(BindHelper.bindBoundBeanProperty("address", infoBinding, ServerInfo.class, String.class));
        }

        UILabel port = find("port", UILabel.class);
        if (port != null) {
            port.bindText(new IntToStringBinding(BindHelper.bindBoundBeanProperty("port", infoBinding, ServerInfo.class, int.class)));
        }

        ReadOnlyBinding<Boolean> localSelectedServerOnly = new ReadOnlyBinding<Boolean>() {
            @Override
            public Boolean get() {
                if (infoBinding.get() == null) {
                    return false;
                }

                return locals.contains(infoBinding.get());
            }
        };

        WidgetUtil.trySubscribe(this, "add", new ActivateEventListener() {
            @Override
            public void onActivated(UIWidget button) {
                getManager().pushScreen(AddServerPopup.ASSET_URI);
            }
        });

        UIButton edit = find("edit", UIButton.class);
        if (edit != null) {
            edit.bindEnabled(localSelectedServerOnly);
            edit.subscribe(new ActivateEventListener() {
                @Override
                public void onActivated(UIWidget button) {
//                  AddServerPopup popup = getManager().pushScreen(AddServerPopup.ASSET_URI, AddServerPopup.class);
//                  popup.setServerInfo(infoBinding.get());
                  ServerInfo item = serverList.getSelection();
                  if (!extInfo.containsKey(item)) {
                      Future<ServerInfoMessage> futureInfo = networkSystem.requestInfo(item.getAddress(), item.getPort());
                      extInfo.put(item, futureInfo);
                  }
                }
            });
        }

        UIButton removeButton = find("remove", UIButton.class);
        if (removeButton != null) {
            removeButton.bindEnabled(localSelectedServerOnly);
            removeButton.subscribe(new ActivateEventListener() {
                @Override
                public void onActivated(UIWidget button) {
                    if (serverList.getSelection() != null) {
                        config.getNetwork().remove(serverList.getSelection());
                        serverList.setSelection(null);
                    }
                }
            });
        }

        UIButton joinButton = find("join", UIButton.class);
        if (joinButton != null) {
            joinButton.bindEnabled(new ReadOnlyBinding<Boolean>() {

                @Override
                public Boolean get() {
                    return infoBinding.get() != null;
                }
            });
            joinButton.subscribe(new ActivateEventListener() {
                @Override
                public void onActivated(UIWidget button) {
                    config.save();
                    ServerInfo item = serverList.getSelection();
                    if (item != null) {
                        join(item.getAddress(), item.getPort());
                    }
                }
            });
        }

        UILabel modules = find("modules", UILabel.class);
        modules.bindText(new ReadOnlyBinding<String>() {
            @Override
            public String get() {
                Future<ServerInfoMessage> info = extInfo.get(serverList.getSelection());
                if (info != null) {
                    if (info.isDone()) {
                        try {
                            List<String> codedModInfo = new ArrayList<>();
                            ModuleRegistry reg = moduleManager.getRegistry();
                            for (NameVersion entry : info.get().getModuleList()) {
                                boolean isInstalled = reg.getModule(entry.getName(), entry.getVersion()) != null;
                                Color color = isInstalled ? Color.GREEN : Color.RED;
                                codedModInfo.add(FontColor.getColored(entry.toString(), color));
                            }
                            return "aa";//Joiner.on('\n').join(codedModInfo);
                        } catch (InterruptedException | ExecutionException e) {
                            e.printStackTrace(); // never!
                        }
                    } else {
                        return "requested";
                    }
                }
                return null;
            }
        });

        UILabel worlds = find("worlds", UILabel.class);
        worlds.bindText(new ReadOnlyBinding<String>() {
            @Override
            public String get() {
                Future<ServerInfoMessage> info = extInfo.get(serverList.getSelection());
                if (info != null) {
                    if (info.isDone()) {
                        try {
                            List<String> codedWorldInfo = new ArrayList<>();
                            for (WorldInfo wi : info.get().getWorldInfoList()) {
                                codedWorldInfo.add(wi.getTitle());
                                codedWorldInfo.add("Time: " + wi.getTime() / 3600_000);
                                codedWorldInfo.add(wi.getWorldGenerator() + "-" + wi.getSeed());
                            }
                            return Joiner.on('\n').join(codedWorldInfo);
                        } catch (InterruptedException | ExecutionException e) {
                            e.printStackTrace(); // never!
                        }
                    } else {
                        return "requested";
                    }
                }
                return null;
            }
        });


        UILabel downloadLabel = find("download", UILabel.class);
        if (downloadLabel != null) {
            downloadLabel.bindText(new ReadOnlyBinding<String>() {
                @Override
                public String get() {
                    return downloader.getStatus();
                }
            });
        }
    }

}
