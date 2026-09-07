# Third-party notices

This project is licensed under the Apache License, Version 2.0 (see `LICENSE`), and its copyright and
attribution notices are in `NOTICE`.

The distributed provider jar (`keycloak-event-forwarder.jar`) is an uber jar: it contains the compiled
classes of the dependency below in addition to this project's own classes. That dependency is
redistributed under the same Apache License, Version 2.0, whose full text is in `LICENSE` and inside
the jar at `META-INF/LICENSE`.

Every other dependency (Keycloak, SLF4J, Micrometer) is `provided`: it is used at compile time and
supplied by the Keycloak runtime, so none of it is redistributed by this project.

## com.rabbitmq:amqp-client 5.23.0

RabbitMQ Java Client, Copyright (c) Broadcom. All Rights Reserved. The term Broadcom refers to
Broadcom Inc. and/or its subsidiaries.

Triple-licensed by its authors under the Apache License, Version 2.0, the Mozilla Public License 2.0
and the GNU General Public License, version 2. This project redistributes it under the **Apache
License, Version 2.0**, whose full text is in `LICENSE`.

- Project: https://github.com/rabbitmq/rabbitmq-java-client
- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0.html
- Mozilla Public License 2.0: https://www.mozilla.org/en-US/MPL/2.0/
- GNU General Public License v2: https://www.gnu.org/licenses/gpl-2.0.txt
