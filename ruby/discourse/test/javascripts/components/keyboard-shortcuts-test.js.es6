import DiscourseURL from "discourse/lib/url";

var testMouseTrap;
import KeyboardShortcuts from "discourse/lib/keyboard-shortcuts";

QUnit.module("lib:keyboard-shortcuts", {
  beforeEach() {
    var _bindings = {};

    testMouseTrap = {
      bind: function(bindings, callback) {
        var registerBinding = _.bind(function(binding) {
          _bindings[binding] = callback;
        }, this);

        if (_.isArray(bindings)) {
          _.each(bindings, registerBinding, this);
        } else {
          registerBinding(bindings);
        }
      },

      trigger: function(binding) {
        _bindings[binding].call();
      }
    };

    sandbox.stub(DiscourseURL, "routeTo");

    $("#qunit-fixture").html(
      [
        "<article class='topic-post selected'>",
        "<a class='post-date'></a>" + "</article>",
        "<div class='notification-options'>",
        "  <ul>",
        "    <li data-id='0'><a></a></li>",
        "    <li data-id='1'><a></a></li>",
        "    <li data-id='2'><a></a></li>",
        "    <li data-id='3'><a></a></li>",
        "  </ul>",
        "</div>",
        "<table class='topic-list'>",
        "  <tr class='topic-list-item selected'><td>",
        "    <a class='title'></a>",
        "  </td></tr>",
        "</table>",
        "<div id='topic-footer-buttons'>",
        "  <button class='star'></button>",
        "  <button class='create'></button>",
        "  <button class='share'></button>",
        "  <button id='dismiss-new-top'></button>",
        "  <button id='dismiss-topics-top'></button>",
        "</div>",
        "<div class='alert alert-info clickable'></div>",
        "<button id='create-topic'></button>",
        "<div id='user-notifications'></div>",
        "<div id='toggle-hamburger-menu'></div>",
        "<div id='search-button'></div>",
        "<div id='current-user'></div>",
        "<div id='keyboard-help'></div>"
      ].join("\n")
    );
  },

  afterEach() {
    $("#qunit-scratch").html("");
  }
});

var pathBindings = KeyboardShortcuts.PATH_BINDINGS;

_.each(pathBindings, function(path, binding) {
  var testName = binding + " goes to " + path;

  test(testName, function(assert) {
    KeyboardShortcuts.bindEvents(testMouseTrap);
    testMouseTrap.trigger(binding);

    assert.ok(DiscourseURL.routeTo.calledWith(path));
  });
});

var clickBindings = KeyboardShortcuts.CLICK_BINDINGS;

_.each(clickBindings, function(selector, binding) {
  var bindings = binding.split(",");

  var testName = binding + " clicks on " + selector;

  test(testName, function(assert) {
    KeyboardShortcuts.bindEvents(testMouseTrap);
    $(selector).on("click", function() {
      assert.ok(true, selector + " was clicked");
    });

    _.each(
      bindings,
      function(b) {
        testMouseTrap.trigger(b);
      },
      this
    );
  });
});

var functionBindings = KeyboardShortcuts.FUNCTION_BINDINGS;

_.each(functionBindings, function(func, binding) {
  var testName = binding + " calls " + func;

  test(testName, function(assert) {
    sandbox.stub(KeyboardShortcuts, func, function() {
      assert.ok(true, func + " is called when " + binding + " is triggered");
    });
    KeyboardShortcuts.bindEvents(testMouseTrap);

    testMouseTrap.trigger(binding);
  });
});

QUnit.test("selectDown calls _moveSelection with 1", assert => {
  var spy = sandbox.spy(KeyboardShortcuts, "_moveSelection");

  KeyboardShortcuts.selectDown();
  assert.ok(spy.calledWith(1), "_moveSelection is called with 1");
});

QUnit.test("selectUp calls _moveSelection with -1", assert => {
  var spy = sandbox.spy(KeyboardShortcuts, "_moveSelection");

  KeyboardShortcuts.selectUp();
  assert.ok(spy.calledWith(-1), "_moveSelection is called with -1");
});

QUnit.test("goBack calls history.back", assert => {
  var called = false;
  sandbox.stub(history, "back", function() {
    called = true;
  });

  KeyboardShortcuts.goBack();
  assert.ok(called, "history.back is called");
});

QUnit.test("nextSection calls _changeSection with 1", assert => {
  var spy = sandbox.spy(KeyboardShortcuts, "_changeSection");

  KeyboardShortcuts.nextSection();
  assert.ok(spy.calledWith(1), "_changeSection is called with 1");
});

QUnit.test("prevSection calls _changeSection with -1", assert => {
  var spy = sandbox.spy(KeyboardShortcuts, "_changeSection");

  KeyboardShortcuts.prevSection();
  assert.ok(spy.calledWith(-1), "_changeSection is called with -1");
});
