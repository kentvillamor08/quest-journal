package com.questjournal.client.editor;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

import com.questjournal.core.model.JsonEnum;
import com.questjournal.core.model.RegistryKind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * One row of an editor form, bound to a property of {@code T} through getter/setter lambdas. Because every editor
 * (quest, objective, reward, lore page, dialogue, ...) is just a list of Fields, the whole editor shares one form screen.
 */
public abstract class Field<T> {
    public final String label;
    public Predicate<T> visible = t -> true;

    protected Field(String label) {
        this.label = label;
    }

    public Field<T> when(Predicate<T> p) {
        this.visible = p;
        return this;
    }

    abstract AbstractWidget create(FormScreen<T> screen, T obj);

    // ------------------------------------------------------------------ factories

    public static <T> Field<T> text(String label, Function<T, String> get, BiConsumer<T, String> set, int max) {
        return new Field<T>(label) {
            @Override
            AbstractWidget create(FormScreen<T> s, T o) {
                EditBox b = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 16, Component.literal(label));
                b.setMaxLength(max);
                b.setValue(get.apply(o));
                b.setResponder(v -> set.accept(o, v));
                return b;
            }
        };
    }

    public static <T> Field<T> integer(String label, ToIntFunction<T> get, java.util.function.ObjIntConsumer<T> set, int min, int max) {
        return new Field<T>(label) {
            @Override
            AbstractWidget create(FormScreen<T> s, T o) {
                EditBox b = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 16, Component.literal(label));
                b.setMaxLength(10);
                b.setFilter(v -> v.isEmpty() || v.equals("-") || v.matches("-?\\d{1,9}"));
                b.setValue(String.valueOf(get.applyAsInt(o)));
                b.setResponder(v -> {
                    try {
                        set.accept(o, Math.max(min, Math.min(max, Integer.parseInt(v))));
                    } catch (NumberFormatException ignored) {
                        // "" or "-" while typing: keep the previous value until a valid number is entered
                    }
                });
                return b;
            }
        };
    }

    public static <T> Field<T> decimal(String label, ToDoubleFunction<T> get, java.util.function.ObjDoubleConsumer<T> set) {
        return new Field<T>(label) {
            @Override
            AbstractWidget create(FormScreen<T> s, T o) {
                EditBox b = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 16, Component.literal(label));
                b.setMaxLength(14);
                b.setFilter(v -> v.matches("-?\\d{0,8}(\\.\\d{0,3})?"));
                double d = get.applyAsDouble(o);
                b.setValue(d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d));
                b.setResponder(v -> {
                    try {
                        set.accept(o, Double.parseDouble(v));
                    } catch (NumberFormatException ignored) {
                        // incomplete number such as "-" or "1." while typing
                    }
                });
                return b;
            }
        };
    }

    public static <T> Field<T> toggle(String label, Predicate<T> get, BiConsumer<T, Boolean> set) {
        return new Field<T>(label) {
            @Override
            AbstractWidget create(FormScreen<T> s, T o) {
                return Button.builder(Component.literal(get.test(o) ? "Yes" : "No"), b -> {
                    set.accept(o, !get.test(o));
                    s.rebuild();
                }).bounds(0, 0, 100, 16).build();
            }
        };
    }

    public static <T, E extends Enum<E> & JsonEnum> Field<T> cycle(String label, Function<T, E> get, BiConsumer<T, E> set) {
        return new Field<T>(label) {
            @Override
            AbstractWidget create(FormScreen<T> s, T o) {
                return Button.builder(Component.literal(get.apply(o).label()), b -> {
                    set.accept(o, JsonEnum.next(get.apply(o)));
                    s.rebuild();
                }).bounds(0, 0, 100, 16).build();
            }
        };
    }

    public static <T> Field<T> button(String label, Function<T, String> text, Consumer<FormScreen<T>> click) {
        return new Field<T>(label) {
            @Override
            AbstractWidget create(FormScreen<T> s, T o) {
                return Button.builder(Component.literal(text.apply(o)), b -> click.accept(s)).bounds(0, 0, 100, 16).build();
            }
        };
    }

    /** Opens the registry browser; shows the chosen id (which may belong to a mod that is not installed). */
    public static <T> Field<T> pick(String label, Function<T, String> get, BiConsumer<T, String> set, Function<T, RegistryKind> kind) {
        return new Field<T>(label) {
            @Override
            AbstractWidget create(FormScreen<T> s, T o) {
                String cur = get.apply(o);
                return Button.builder(Component.literal(cur.isEmpty() ? "Choose..." : cur), b ->
                        Minecraft.getInstance().setScreen(new PickerScreen(s, "Choose " + kind.apply(o).label.toLowerCase(),
                                PickerScreen.entriesFor(kind.apply(o)), cur, id -> {
                                    set.accept(o, id);
                                    s.rebuild();
                                }, true))).bounds(0, 0, 100, 16).build();
            }
        };
    }

    /**
     * Typed builder: {@code Field.Of<Quest> b = new Field.Of<>(); b.text(...).when(...)} lets Java infer the lambda
     * parameter types (a bare generic factory call followed by .when(...) would infer Object).
     */
    public static final class Of<T> {
        public Field<T> text(String label, Function<T, String> get, BiConsumer<T, String> set, int max) {
            return Field.<T>text(label, get, set, max);
        }

        public Field<T> integer(String label, ToIntFunction<T> get, java.util.function.ObjIntConsumer<T> set, int min, int max) {
            return Field.<T>integer(label, get, set, min, max);
        }

        public Field<T> decimal(String label, ToDoubleFunction<T> get, java.util.function.ObjDoubleConsumer<T> set) {
            return Field.<T>decimal(label, get, set);
        }

        public Field<T> toggle(String label, Predicate<T> get, BiConsumer<T, Boolean> set) {
            return Field.<T>toggle(label, get, set);
        }

        public <E extends Enum<E> & JsonEnum> Field<T> cycle(String label, Function<T, E> get, BiConsumer<T, E> set) {
            return Field.<T, E>cycle(label, get, set);
        }

        public Field<T> button(String label, Function<T, String> text, Consumer<FormScreen<T>> click) {
            return Field.<T>button(label, text, click);
        }

        public Field<T> pick(String label, Function<T, String> get, BiConsumer<T, String> set, Function<T, RegistryKind> kind) {
            return Field.<T>pick(label, get, set, kind);
        }
    }
}
