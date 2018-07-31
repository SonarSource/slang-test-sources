import deprecated from "discourse-common/lib/deprecated";

export default class Connector {
  constructor(widget, opts) {
    this.widget = widget;
    this.opts = opts;
  }

  init() {
    const $elem = $(`<div class='widget-connector'></div>`);
    const elem = $elem[0];

    const { opts, widget } = this;
    Ember.run.next(() => {
      const mounted = widget._findView();

      if (opts.templateName) {
        deprecated(
          `Using a 'templateName' for a connector is deprecated. Use 'component' instead [${
            opts.templateName
          }]`
        );
      }

      const container = Ember.getOwner
        ? Ember.getOwner(mounted)
        : mounted.container;

      let view;

      if (opts.component) {
        const connector = widget.register.lookupFactory(
          "component:connector-container"
        );
        view = connector.create({
          layoutName: `components/${opts.component}`,
          model: widget.findAncestorModel()
        });
      }

      if (opts.templateName) {
        let context;
        if (opts.context === "model") {
          const model = widget.findAncestorModel();
          context = model;
        }

        view = Ember.View.create({
          container: container || widget.register,
          templateName: opts.templateName,
          context
        });
      }

      if (view) {
        if (Ember.setOwner) {
          Ember.setOwner(view, Ember.getOwner(mounted));
        }
        mounted._connected.push(view);
        view.renderer.appendTo(view, $elem[0]);
      }
    });

    return elem;
  }

  update() {}
}

Connector.prototype.type = "Widget";
