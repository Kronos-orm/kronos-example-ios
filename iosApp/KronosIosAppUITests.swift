import XCTest

final class KronosIosAppUITests: XCTestCase {
    func testDisplaysPersistedSQLiteRow() {
        let app = XCUIApplication()
        app.launch()
        let result = app.staticTexts["sqlite-result"]
        XCTAssertTrue(result.waitForExistence(timeout: 30))
        let expected = "SQLite connected: id=1, name=Kronos iOS"
        let persisted = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", expected),
            object: result
        )
        XCTAssertEqual(XCTWaiter.wait(for: [persisted], timeout: 60), .completed)
        XCTAssertEqual(result.label, expected)
    }
}
