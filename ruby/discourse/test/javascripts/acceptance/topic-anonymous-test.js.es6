import { acceptance } from "helpers/qunit-helpers";
acceptance("Topic - Anonymous");

QUnit.test("Enter a Topic", async assert => {
  await visit("/t/internationalization-localization/280/1");
  assert.ok(exists("#topic"), "The topic was rendered");
  assert.ok(exists("#topic .cooked"), "The topic has cooked posts");
  assert.ok(
    find(".shared-draft-notice").length === 0,
    "no shared draft unless there's a dest category id"
  );
});

QUnit.test("Enter without an id", async assert => {
  await visit("/t/internationalization-localization");
  assert.ok(exists("#topic"), "The topic was rendered");
});

QUnit.test("Enter a 404 topic", async assert => {
  await visit("/t/not-found/404");
  assert.ok(!exists("#topic"), "The topic was not rendered");
  assert.ok(
    find(".not-found").text() === "not found",
    "it renders the error message"
  );
});

QUnit.test("Enter without access", async assert => {
  await visit("/t/i-dont-have-access/403");
  assert.ok(!exists("#topic"), "The topic was not rendered");
  assert.ok(exists(".topic-error"), "An error message is displayed");
});

QUnit.test("Enter with 500 errors", async assert => {
  await visit("/t/throws-error/500");
  assert.ok(!exists("#topic"), "The topic was not rendered");
  assert.ok(exists(".topic-error"), "An error message is displayed");
});
