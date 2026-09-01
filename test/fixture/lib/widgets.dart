/// A stand-in for `package:flutter/material.dart`.
///
/// Nothing here is a real widget -- what matters is that each declaration has
/// the *shape* the resolve suites probe in Flutter: a documented class whose
/// `this.x` named parameters inherit their field's doc, a static const on a
/// class, an enum with documented values, and an abstract getter. The names
/// deliberately differ from Flutter's so a failure says which target ran.
library;

/// Describes part of the user interface.
abstract class Widget {
  /// Abstract const constructor, so subclasses can be const.
  const Widget();
}

/// A widget that aligns its child within itself.
class Align extends Widget {
  /// Creates an alignment widget.
  const Align({this.child, this.fit});

  /// The widget below this one in the tree.
  final Widget? child;

  /// How to place the child along the main axis.
  final Fit? fit;
}

/// A widget that centers its child within itself.
///
/// Reached in the suites as a bare reference -- `m/Middle` -- which is what
/// makes its `extends` clause part of the assertion.
class Middle extends Align {
  /// Creates a widget that centers its child.
  const Middle({super.child});
}

/// An application that uses material design.
class App extends Widget {
  /// Creates an App.
  ///
  /// The [home] widget is the one shown first.
  const App({
    this.title,
    this.home,
    this.theme,
    this.initialRoute,
    this.debugShowBanner,
  });

  /// A one-line description used by the device to identify the app.
  final String? title;

  /// The widget for the default route of the app.
  final Widget? home;

  /// Colors and typography to use for material widgets.
  final Tint? theme;

  /// The name of the first route to show.
  final String? initialRoute;

  /// Whether to show the "debug" banner in the corner.
  final bool? debugShowBanner;
}

/// Implements the basic material design visual layout structure.
///
/// This is the doc that has to survive an EDN round trip intact: it quotes
/// `"a string"`, escapes a backslash \ on its own, keeps a tab ->	<- and a
/// non-ASCII dash -- and none of that may come back as anything else.
class Panel extends Widget {
  /// Creates a visual scaffold for material design widgets.
  const Panel({
    this.body,
    this.appBar,
    this.floatingButton,
    this.backgroundColor,
    this.resizeToAvoidBottomInset,
  });

  /// The primary content of the scaffold.
  ///
  /// Displayed below the [appBar], and behind the [floatingButton].
  final Widget? body;

  /// An app bar to display at the top of the scaffold.
  final Widget? appBar;

  /// A button displayed floating above [body].
  final Widget? floatingButton;

  /// The color of the [Panel] behind everything.
  final Tint? backgroundColor;

  /// Whether the body should resize when the keyboard appears.
  final bool? resizeToAvoidBottomInset;

  /// Describes the part of the user interface represented by this widget.
  Widget build(Host host) => this;

  /// Finds the closest enclosing scaffold, or null.
  static Panel? maybeOf(Host host) => null;
}

/// A run of text with a single style.
///
/// The [Label] widget displays a string of text with a single style. The
/// string might break across multiple lines, or might all be displayed on the
/// same line, depending on the layout constraints.
class Label extends Widget {
  /// Creates a run of styled text.
  const Label(this.data, {this.style, this.maxLines});

  /// Creates a run of text with several styles.
  ///
  /// Reached in the suites as `m/Label.rich` -- the named-constructor shape,
  /// and the one a `m/Label.` prefix completes to.
  const Label.rich(this.data, {this.style, this.maxLines});

  /// The text to display.
  final String data;

  /// If non-null, the style to use for this text.
  ///
  /// Inherited by the `this.style` parameter of the constructor above, which
  /// is the whole point of the doc-source patch in the helper.
  final LabelStyle? style;

  /// An optional maximum number of lines for the text to span.
  final int? maxLines;

  /// Describes the part of the user interface represented by this widget.
  Widget build(Host host) => this;
}

/// A button that invokes callbacks when it is used.
class Button extends Widget {
  /// Creates a button around a required child.
  const Button({
    required this.child,
    this.onPressed,
    this.onBuild,
    this.compare,
  });

  /// The widget displayed inside the button.
  final Widget child;

  /// Called when the button is pressed.
  final void Function()? onPressed;

  /// Called while the button is being built.
  final void Function({required Host context, int index})? onBuild;

  /// Compares two values and returns an integer result.
  final int Function<T>(T, T)? compare;
}

/// An immutable style describing how to format and paint text.
class LabelStyle {
  /// Creates a text style.
  const LabelStyle({this.color, this.fontSize, this.inherit = true});

  /// The color to use when painting the text.
  final Tint? color;

  /// The size of glyphs, in logical pixels.
  final double? fontSize;

  /// Whether null values in this style are replaced with their counterparts.
  final bool inherit;
}

/// A 32 bit immutable color value.
class Tint {
  /// Creates a color from an ARGB value.
  const Tint(this.value);

  /// A 32 bit value holding the color's alpha, red, green and blue channels.
  final int value;
}

/// Colors from the material design palette.
///
/// Reached in the suites as `m.Palette/red` -- a static member through a
/// dotted alias.
class Palette {
  Palette._();

  /// The red primary color and its shades.
  static const Tint red = Tint(0xFFF44336);

  /// The blue primary color and its shades.
  static const Tint blue = Tint(0xFF2196F3);
}

/// How children should be placed along the main axis.
enum Fit {
  /// Place the children as close to the middle of the main axis as possible.
  center,

  /// Place the children as close to the start of the main axis as possible.
  start,

  /// Place the children as close to the end of the main axis as possible.
  end,
}

/// A handle to the location of a widget in the widget tree.
///
/// Its members are getters, so every one of them is a *synthetic* field whose
/// doc and position live on the accessor -- the case `deSynth` exists for.
abstract class Host {
  /// The current configuration this host was built from.
  Widget get widget;

  /// Whether this host is currently mounted in the tree.
  bool get mounted;
}
