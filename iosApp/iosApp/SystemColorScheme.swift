import SwiftUI
import UIKit

/// Reports the OS-level light/dark setting, independent of any
/// `preferredColorScheme` override already applied above it in the view
/// hierarchy. Needed because `preferredColorScheme(nil)` doesn't reliably
/// clear a previously-set override on a *presented* sheet (a known
/// SwiftUI/UIKit bug) — call sites resolve "system" to this concrete value
/// themselves instead of passing `nil`.
private struct SystemColorSchemeReader: UIViewRepresentable {
    let onChange: (ColorScheme) -> Void

    func makeUIView(context: Context) -> ObserverView {
        let view = ObserverView()
        view.isUserInteractionEnabled = false
        view.onChange = onChange
        return view
    }

    func updateUIView(_ uiView: ObserverView, context: Context) {
        uiView.onChange = onChange
    }

    final class ObserverView: UIView {
        var onChange: ((ColorScheme) -> Void)?
        private weak var observedScene: UIWindowScene?
        private var traitRegistration: (any UITraitChangeRegistration)?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            let scene = window?.windowScene
            if scene !== observedScene {
                if let registration = traitRegistration {
                    observedScene?.unregisterForTraitChanges(registration)
                    traitRegistration = nil
                }
                observedScene = scene
                if let scene {
                    traitRegistration = scene.registerForTraitChanges([UITraitUserInterfaceStyle.self]) { [weak self] (scene: UIWindowScene, _) in
                        self?.report(scene: scene)
                    }
                }
            }
            if let scene {
                report(scene: scene)
            }
        }

        // The scene's screen trait collection tracks the physical device
        // setting and isn't affected by any app-level overrideUserInterfaceStyle,
        // unlike `self.traitCollection`.
        private func report(scene: UIWindowScene) {
            onChange?(scene.screen.traitCollection.userInterfaceStyle == .dark ? .dark : .light)
        }
    }
}

extension View {
    /// Keeps `binding` in sync with the OS light/dark setting.
    func observeSystemColorScheme(_ binding: Binding<ColorScheme>) -> some View {
        background(SystemColorSchemeReader(onChange: { binding.wrappedValue = $0 }))
    }
}
