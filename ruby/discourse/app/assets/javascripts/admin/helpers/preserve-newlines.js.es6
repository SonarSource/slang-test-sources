import { htmlHelper } from "discourse-common/lib/helpers";
import { escapeExpression } from "discourse/lib/utilities";

export default htmlHelper(str => escapeExpression(str).replace(/\n/g, "<br>"));
