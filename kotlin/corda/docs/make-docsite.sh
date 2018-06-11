#!/usr/bin/env bash
# The purpose of this file is to make the docsite in a python virtualenv
# You can call it manually if running make manually, otherwise gradle will run it for you

# Activate the virtualenv
if [ -d "virtualenv/bin" ]
then
    # it's a Unix system
    source virtualenv/bin/activate
else
    source virtualenv/Scripts/activate
fi

# TODO: The PDF rendering is pretty ugly and can be improved a lot.
echo "Generating PDF document ..."
make pdf

echo "Generating HTML pages ..."
make html

echo "Moving PDF file into place ..."
mv $PWD/build/pdf/corda-developer-site.pdf $PWD/build/html/_static/corda-developer-site.pdf

