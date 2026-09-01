// Enumerating a library's public top-level names.
//
// NOT vendored. `analyzer.dart` beside this file is a patched copy of
// ClojureDart's `resources/analyzer.dart`, and every line added to it is a
// line to re-apply by hand on the next upstream merge (vendor/README.md). So
// the machinery lives here, in a file upstream has no opinion about, and
// `analyzer.dart` gains only an import and a case that delegates.
//
// This is the one question the daemon cannot answer from `elt`: that command
// resolves a name you already have, and completing `m/AnimatedCro` needs the
// set of names there are.

import 'package:analyzer/dart/analysis/analysis_context_collection.dart';
import 'package:analyzer/dart/analysis/results.dart';
import 'package:analyzer/dart/element/element.dart';
import 'package:analyzer/file_system/overlay_file_system.dart';

/// EDN-escapes [s]. A Dart identifier never needs it, but the map this builds
/// is read by a reader that will not forgive the one that does.
String _s(String s) =>
    '"' + s.replaceAll("\\", "\\\\").replaceAll("\"", "\\\"") + '"';

/// What a completion list shows as the icon beside a name. Deliberately coarse
/// -- the full signature comes later, and only for the row the user lands on.
///
/// Ordered specific-first: in analyzer 6 an enum and a mixin are their own
/// element types rather than kinds of class, but the extension types are not.
String _kind(Element e) {
  if (e is EnumElement) return ":enum";
  if (e is MixinElement) return ":mixin";
  if (e is ExtensionTypeElement) return ":class";
  if (e is ExtensionElement) return ":extension";
  if (e is ClassElement) return ":class";
  if (e is TypeAliasElement) return ":typedef";
  if (e is FunctionElement) return ":function";
  if (e is PropertyAccessorElement) return ":field";
  if (e is TopLevelVariableElement) return ":field";
  return ":unknown";
}

/// The public top-level names [lib] exports, as an EDN map of name -> kind.
/// `nil` when the library does not resolve.
///
/// Reached through the same one-line overlay `retrieveElement` uses: importing
/// the library into a scratch file is what makes the analyzer resolve it, and
/// `exportNamespace` is then everything that import brought into scope --
/// which is exactly what an alias can name.
Future<String?> retrieveNames(
    OverlayResourceProvider resourceProvider,
    AnalysisContextCollection coll,
    String lib,
    String projectDirectoryPath) async {
  final pathContext = resourceProvider.pathContext;
  final sep = pathContext.separator;
  final filePath = pathContext
      .normalize("${projectDirectoryPath}${sep}lib${sep}cljdfuzzysearch.dart");
  resourceProvider.setOverlay(filePath,
      content: "import '${lib}' as libalias;\n",
      modificationStamp: DateTime.now().millisecondsSinceEpoch);
  final context = coll.contextFor(filePath);
  context.changeFile(filePath);
  await context.applyPendingFileChanges();
  final result = await context.currentSession
      .getLibraryByUri(pathContext.toUri(filePath).toString());
  if (result is! LibraryElementResult) return null;

  final rootLib = result.element.importedLibraries.first;
  // An import that does not resolve leaves the implicit `dart:core` as the
  // first one, so without this a typo in the `ns` form -- or a URI half-typed
  // -- answers with the whole of `dart:core` under the alias, confidently and
  // silently. Ask whether we got the library we asked for.
  if (rootLib.identifier != lib) return null;

  final sb = StringBuffer("{");
  rootLib.exportNamespace.definedNames.forEach((name, e) {
    // A setter is exported under `foo=`, which is not a name anything can be
    // completed to -- its getter is already here under `foo`.
    if (name.endsWith("=")) return;
    if (!e.isPublic) return;
    sb..write(_s(name))..write(" ")..write(_kind(e))..write("\n");
  });
  sb.write("}");
  return sb.toString();
}
