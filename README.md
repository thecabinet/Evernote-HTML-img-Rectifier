#Evernote HTML img Rectifier

A library and utilty for replacing HTML img elements in your Evernotes with
actual image resources.  When you create a note from a webpage with Evernote
Web Clipper (whether the bookmarklet or browser extension), it saves the HTML
of the page you're viewing as the note.  It *doesn't* take the resources from
that page and embed them as Evernote resources, meaning that any images in
the page will only work as long as the image's owner continues to host them.

Evernote HTML img Rectifier processes notes in your account, replacing any
HTML img tags with Evernote en-media resources, thus preserving the images
for posterity.

