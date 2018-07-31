import { createWidget } from "discourse/widgets/widget";
import hbs from "discourse/widgets/hbs-compiler";

createWidget("header-contents", {
  tagName: "div.contents.clearfix",
  template: hbs`
    {{attach widget="home-logo" attrs=attrs}}
    <div class="panel clearfix">{{yield}}</div>
    {{#if attrs.topic}}
      {{attach widget="header-topic-info" attrs=attrs}}
    {{/if}}
  `
});
