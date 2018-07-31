QUnit.module("rest-model");

import createStore from "helpers/create-store";
import RestModel from "discourse/models/rest";

QUnit.test("munging", assert => {
  const store = createStore();
  const Grape = RestModel.extend();
  Grape.reopenClass({
    munge: function(json) {
      json.inverse = 1 - json.percent;
      return json;
    }
  });

  var g = Grape.create({ store, percent: 0.4 });
  assert.equal(g.get("inverse"), 0.6, "it runs `munge` on `create`");
});

QUnit.test("update", assert => {
  const store = createStore();
  return store.find("widget", 123).then(function(widget) {
    assert.equal(widget.get("name"), "Trout Lure");
    assert.ok(!widget.get("isSaving"), "it is not saving");

    const promise = widget.update({ name: "new name" });
    assert.ok(widget.get("isSaving"), "it is saving");

    promise.then(function(result) {
      assert.ok(!widget.get("isSaving"), "it is no longer saving");
      assert.equal(widget.get("name"), "new name");

      assert.ok(result.target, "it has a reference to the record");
      assert.equal(result.target.name, widget.get("name"));
    });
  });
});

QUnit.test("updating simultaneously", assert => {
  assert.expect(2);

  const store = createStore();
  return store.find("widget", 123).then(function(widget) {
    const firstPromise = widget.update({ name: "new name" });
    const secondPromise = widget.update({ name: "new name" });
    firstPromise.then(function() {
      assert.ok(true, "the first promise succeeeds");
    });

    secondPromise.catch(function() {
      assert.ok(true, "the second promise fails");
    });
  });
});

QUnit.test("save new", assert => {
  const store = createStore();
  const widget = store.createRecord("widget");

  assert.ok(widget.get("isNew"), "it is a new record");
  assert.ok(!widget.get("isCreated"), "it is not created");
  assert.ok(!widget.get("isSaving"), "it is not saving");

  const promise = widget.save({ name: "Evil Widget" });
  assert.ok(widget.get("isSaving"), "it is not saving");

  return promise.then(function(result) {
    assert.ok(!widget.get("isSaving"), "it is no longer saving");
    assert.ok(widget.get("id"), "it has an id");
    assert.ok(widget.get("name"), "Evil Widget");
    assert.ok(widget.get("isCreated"), "it is created");
    assert.ok(!widget.get("isNew"), "it is no longer new");

    assert.ok(result.target, "it has a reference to the record");
    assert.equal(result.target.name, widget.get("name"));
  });
});

QUnit.test("creating simultaneously", assert => {
  assert.expect(2);

  const store = createStore();
  const widget = store.createRecord("widget");

  const firstPromise = widget.save({ name: "Evil Widget" });
  const secondPromise = widget.save({ name: "Evil Widget" });
  firstPromise.then(function() {
    assert.ok(true, "the first promise succeeeds");
  });

  secondPromise.catch(function() {
    assert.ok(true, "the second promise fails");
  });
});

QUnit.test("destroyRecord", assert => {
  const store = createStore();
  return store.find("widget", 123).then(function(widget) {
    widget.destroyRecord().then(function(result) {
      assert.ok(result);
    });
  });
});
