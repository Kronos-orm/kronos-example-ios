import SwiftUI
import KronosIosExample

@main
struct KronosIosApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @State private var result = "Opening SQLite…"

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Kronos SQLite").font(.title)
            Text(result).accessibilityIdentifier("sqlite-result")
        }
        .padding()
        .task {
            result = await Task.detached {
                SqliteDemo.shared.insertAndRead(name: "Kronos iOS")
            }.value
        }
    }
}
