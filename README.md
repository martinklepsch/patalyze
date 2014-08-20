# patalyze

A Clojure library designed to ... well, that part is up to you.

## Patent Categorization

1. First-Tier: Patents with Apple as assignee
   - organization == Apple
2. Second-Tier: Patents with Apple employees but not Apple as assignee
   - organization != Apple && inventor in [apple employees]
3. Third-Tier: Patents with Apple employees and people that never published a patent for Apple
   - organization != Apple && inventor in [apple employees] && inventor not in [apple employees]

**To include time as a factor:**
Build employee list for each year by collecting list of employees of previous and following year.
Query for each year separately. 
Potential problem:
   - People that join Apple in the following year might not be considered external
   
Potentially more explicit better solution (depends on ES's query capabilities)

- get first and last publication date for an inventor's name
- combine that with the above queries

## Infrastructure Notes

Caching all snippets on S3 would be useful for partial reindexing

## License

Copyright © 2014 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
